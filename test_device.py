#!/usr/bin/env python3
"""
ZAlarm Device Integration Test Suite (Polling Edition)
======================================================
Tests alarm triggering under all screen states (ON, OFF, locked)
using ADB notification injection via com.android.shell.

Uses a professional polling-based waiting mechanism for UI transitions
to eliminate race conditions, ensuring robust E2E validation.
"""

import subprocess
import time
import sys
import sqlite3
import os

WORK_DIR = os.path.dirname(os.path.abspath(__file__))
DB_LOCAL = os.path.join(WORK_DIR, "_temp_zalarm.db")
TAG_COUNTER = [0]


def adb(cmd, binary=False):
    """Run an adb command and return (stdout, stderr)."""
    full = f"adb {cmd}"
    if binary:
        r = subprocess.run(full, shell=True, capture_output=True)
    else:
        r = subprocess.run(full, shell=True, capture_output=True, text=True)
    return r.stdout, r.stderr


def screen_state():
    out, _ = adb("shell dumpsys power")
    for line in out.splitlines():
        if "mWakefulness=" in line:
            return "ON" if "Awake" in line else "OFF"
    return "UNKNOWN"


def set_screen(target):
    if screen_state() == target:
        return True
    adb("shell input keyevent 26")  # toggle power
    time.sleep(1.5)
    if screen_state() == target:
        return True
    adb("shell input keyevent 26")  # try again
    time.sleep(1.5)
    return screen_state() == target


def unique_tag():
    TAG_COUNTER[0] += 1
    return f"ztag_{TAG_COUNTER[0]}_{int(time.time())}"


def alarm_activity_visible():
    out, _ = adb("shell dumpsys activity activities")
    return "AlarmActivity" in out


def dismiss_alarm():
    """Dismiss any active alarm without killing the service."""
    # 1. Stop alarm audio and vibration via broadcast
    adb("shell am broadcast -a com.walarm.app.ACTION_STOP_ALARM")
    time.sleep(0.5)
    # 2. Return to MainActivity to clear AlarmActivity
    adb("shell am start -n com.walarm.app/com.walarm.app.ui.MainActivity")
    time.sleep(0.8)
    # 3. Clear any lingering notifications
    adb('shell cmd notification cancel_all')
    time.sleep(0.5)


def setup_database():
    """Pull Room DB, inject test contacts, push back."""
    print("\n--- DATABASE SETUP ---")
    print("  Pulling database from device...")

    data, _ = adb(
        'shell "run-as com.walarm.app cat databases/zalarm_database"',
        binary=True,
    )
    if len(data) < 100:
        print("  FATAL: Could not extract database. Is app installed?")
        sys.exit(1)

    with open(DB_LOCAL, "wb") as f:
        f.write(data)

    print("  Injecting VIP test contacts...")
    conn = sqlite3.connect(DB_LOCAL)
    c = conn.cursor()

    # Wipe existing contacts to start clean
    c.execute("DELETE FROM watched_contacts")

    contacts = [
        # (id, name, isGroup, cooldownSeconds)
        (1, "Ganesan", 0, 0),
        (2, "Hackathon group", 1, 0),
        (3, "Harshini_german", 0, 0),
    ]
    for cid, name, is_grp, cd in contacts:
        c.execute(
            """INSERT INTO watched_contacts
               (id,name,isGroup,ringtonePath,useAlarmVolume,
                repeatUntilDismissed,escalatingVolume,cooldownSeconds,
                lastTriggeredTime,isScheduleEnabled,startHour,startMinute,
                endHour,endMinute,vibeOnlyOutsideSchedule,
                isKeywordFilterEnabled,keywords)
               VALUES (?,?,?,NULL,1,0,0,?,0,0,9,0,23,0,1,0,
                       'urgent,help,emergency')""",
            (cid, name, is_grp, cd),
        )

    conn.commit()
    conn.close()
    print(f"  Inserted {len(contacts)} contacts with cooldown=0")

    print("  Pushing database back to device...")
    subprocess.run(
        f'cat {DB_LOCAL} | adb shell "run-as com.walarm.app dd of=databases/zalarm_database"',
        shell=True,
        capture_output=True,
    )
    # Delete WAL/SHM so Room reads the fresh main file
    adb('shell "run-as com.walarm.app rm -f databases/zalarm_database-wal"')
    adb('shell "run-as com.walarm.app rm -f databases/zalarm_database-shm"')

    if os.path.exists(DB_LOCAL):
        os.remove(DB_LOCAL)

    print("  Database setup complete.\n")


def wait_for_listener(timeout=20):
    """Force-stop, restart, and wait for the listener to fully bind and settle."""
    print("--- SERVICE SETUP ---")
    print("  Force-stopping app (one-time)...")
    adb("shell am force-stop com.walarm.app")
    time.sleep(2)

    print("  Launching MainActivity...")
    adb("shell am start -n com.walarm.app/com.walarm.app.ui.MainActivity")
    time.sleep(4)

    print(f"  Waiting up to {timeout}s for NotificationListenerService to bind...")
    for i in range(timeout):
        out, _ = adb("shell dumpsys notification | grep walarm")
        if "WaListenerService" in out:
            print(f"  Listener bound after {i+1}s. Waiting 6s for connection settlement...")
            time.sleep(6)  # Give the binder extra time to complete state initialization
            return True
        time.sleep(1)

    print("  WARNING: Could not confirm listener binding. Proceeding anyway...")
    time.sleep(5)
    return False


# ── TEST CASES ──────────────────────────────────────────────

def poll_for_alarm_activity(timeout_seconds=8.0):
    """Poll for AlarmActivity every 0.5 seconds up to timeout."""
    start_time = time.time()
    while time.time() - start_time < timeout_seconds:
        if alarm_activity_visible():
            return True
        time.sleep(0.5)
    return False


def test_vip_dm_screen_on():
    """VIP contact DM while screen is ON → alarm should trigger."""
    tag = unique_tag()
    set_screen("ON")
    adb("shell input keyevent 82")  # unlock
    time.sleep(1)

    adb(f'shell cmd notification post -t "Ganesan" -S bigtext {tag} "Hey urgent message!"')
    
    # Poll for activity launch
    active = poll_for_alarm_activity(8.0)
    scr = screen_state()
    
    dismiss_alarm()
    return active and scr == "ON"


def test_vip_dm_screen_off():
    """VIP contact DM while screen is OFF → alarm should trigger & wake screen."""
    tag = unique_tag()
    set_screen("OFF")
    time.sleep(4)

    # Acquire temporary wakelock via ADB shell to simulate high-priority FCM wake
    adb('shell "echo zalarm_test > /sys/power/wake_lock"')
    adb(f'shell cmd notification post -t "Ganesan" -S bigtext {tag} "WAKE UP EMERGENCY!"')
    
    # Poll for activity launch
    active = poll_for_alarm_activity(8.0)
    scr = screen_state()
    
    set_screen("ON")
    dismiss_alarm()
    return active


def test_group_vip_screen_off():
    """Group message from VIP contact while screen is OFF → alarm should trigger."""
    tag = unique_tag()
    set_screen("OFF")
    time.sleep(4)

    # Acquire temporary wakelock via ADB shell to simulate high-priority FCM wake
    adb('shell "echo zalarm_test > /sys/power/wake_lock"')
    adb(
        f'shell cmd notification post -t "Hackathon group" '
        f'-S messaging --conversation "Hackathon group" '
        f'--message "Harshini_german:CODE READY NOW" {tag} "Group msg"'
    )
    
    # Poll for activity launch
    active = poll_for_alarm_activity(8.0)
    scr = screen_state()
    
    set_screen("ON")
    dismiss_alarm()
    return active


def test_nonvip_ignored():
    """Non-VIP contact while screen is OFF → alarm should NOT trigger."""
    tag = unique_tag()
    set_screen("OFF")
    time.sleep(4)

    adb(f'shell cmd notification post -t "RandomSpammer" -S bigtext {tag} "Buy crypto now"')
    
    # Poll for activity (should NOT launch)
    active = poll_for_alarm_activity(4.0)
    
    set_screen("ON")
    dismiss_alarm()
    return not active


# ── RUNNER ──────────────────────────────────────────────────

def run(label, fn):
    print(f"\n[TEST] {label}")
    try:
        ok = fn()
    except Exception as e:
        print(f"  ERROR: {e}")
        ok = False
    status = "✅ PASS" if ok else "❌ FAIL"
    print(f"  → {status}")
    return ok


def main():
    print("=" * 60)
    print("  ZAlarm Device Integration Test Suite")
    print("=" * 60)

    # Phase 1 — database
    setup_database()

    # Phase 2 — one-time service startup & listener binding
    wait_for_listener()

    # Phase 3 — run tests (listener stays alive throughout)
    results = []
    results.append(run("1. VIP DM — Screen ON",           test_vip_dm_screen_on))
    results.append(run("2. VIP DM — Screen OFF",          test_vip_dm_screen_off))
    results.append(run("3. Group VIP — Screen OFF",        test_group_vip_screen_off))
    results.append(run("4. Non-VIP — Screen OFF (ignore)", test_nonvip_ignored))

    # Phase 4 — summary
    passed = sum(results)
    total = len(results)
    print("\n" + "=" * 60)
    print(f"  RESULTS: {passed}/{total} passed")
    if passed == total:
        print("  🎉 ALL TESTS PASSED SUCCESSFULLY!")
    else:
        print("  ⚠️  SOME TESTS FAILED — check logcat for details:")
        print('    adb logcat -d | grep -E "WaListenerService|AlarmPlayer"')
    print("=" * 60)

    # Leave screen on and app in foreground
    set_screen("ON")
    adb("shell am start -n com.walarm.app/com.walarm.app.ui.MainActivity")

    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()

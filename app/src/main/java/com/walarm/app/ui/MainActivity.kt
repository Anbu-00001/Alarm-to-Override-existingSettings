package com.walarm.app.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.walarm.app.data.AppDatabase
import com.walarm.app.data.AppSettings
import com.walarm.app.data.SettingsRepository
import com.walarm.app.service.HeartbeatReceiver
import com.walarm.app.service.ServiceRestartWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = AppDatabase.getDatabase(this)

        // Primary keepalive: Doze-resistant AlarmManager heartbeats.
        HeartbeatReceiver.scheduleHeartbeats(this)
        // Backup keepalive: a WorkManager watchdog for when the app is in the foreground.
        scheduleServiceWatchdog()

        setContent {
            ZAlarmTheme {
                // Re-checked on every ON_RESUME, so returning from the system settings
                // screens swaps onboarding for the dashboard automatically. The previous
                // version called setContent() again from onResume(), which threw away and
                // rebuilt the entire composition (and its scroll/tab state) each time.
                val permissionsGranted = rememberCorePermissionsGranted()

                if (permissionsGranted) {
                    DashboardScreen(database = database)
                } else {
                    OnboardingScreen(onFinished = { /* ON_RESUME re-check drives the switch */ })
                }
            }
        }
    }

    private fun scheduleServiceWatchdog() {
        val workRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
            WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            WATCHDOG_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private companion object {
        const val WATCHDOG_WORK_NAME = "ZAlarmServiceRestartWork"
        const val WATCHDOG_INTERVAL_MINUTES = 15L
    }
}

/**
 * True when ZAlarm has the two permissions it genuinely cannot work without:
 * notification-listener access and a battery-optimisation exemption.
 */
private fun corePermissionsGranted(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    val listenerGranted = enabledListeners?.contains(context.packageName) == true

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    return listenerGranted && batteryExempt
}

/** Permission state that refreshes itself whenever the activity resumes. */
@Composable
private fun rememberCorePermissionsGranted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(corePermissionsGranted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = corePermissionsGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return granted
}

@Composable
fun DashboardScreen(database: AppDatabase) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    val contacts by database.contactDao().getAllContactsFlow().collectAsState(initial = emptyList())
    val logs by database.debugLogDao().getRecentLogsFlow().collectAsState(initial = emptyList())
    val settings by SettingsRepository.flow(context).collectAsState(initial = AppSettings())

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(ZAlarmColors.Surface)
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
            ) {
                Text(
                    text = "ZAlarm",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = ZAlarmColors.Primary
                        )
                    }
                ) {
                    DASHBOARD_TABS.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ZAlarmColors.Background)
        ) {
            when (selectedTab) {
                0 -> WatchlistScreen(
                    contacts = contacts,
                    onAddContact = { contact ->
                        coroutineScope.launch { database.contactDao().insertContact(contact) }
                    },
                    onUpdateContact = { contact ->
                        coroutineScope.launch { database.contactDao().updateContact(contact) }
                    },
                    onDeleteContact = { contact ->
                        coroutineScope.launch { database.contactDao().deleteContact(contact) }
                    }
                )

                1 -> DebugLogsScreen(
                    logs = logs,
                    onClearLogs = {
                        coroutineScope.launch { database.debugLogDao().clearLogs() }
                    },
                    onAddContact = { contact ->
                        coroutineScope.launch { database.contactDao().insertContact(contact) }
                    }
                )

                2 -> GlobalSettingsScreen(
                    settings = settings,
                    onSettingsChange = { updated ->
                        coroutineScope.launch { SettingsRepository.save(context, updated) }
                    }
                )
            }
        }
    }
}

private val DASHBOARD_TABS = listOf("Watchlist", "Captured Logs", "Settings")

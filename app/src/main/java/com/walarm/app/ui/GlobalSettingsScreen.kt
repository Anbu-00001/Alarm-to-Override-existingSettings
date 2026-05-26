package com.walarm.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walarm.app.util.OemBatteryHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    nlpEnabled: Boolean,
    onNlpEnabledChanged: (Boolean) -> Unit,
    nlpThreshold: Int,
    onNlpThresholdChanged: (Int) -> Unit,
    overridePhoneCalls: Boolean,
    onOverridePhoneCallsChanged: (Boolean) -> Unit,
    overrideWaCalls: Boolean,
    onOverrideWaCallsChanged: (Boolean) -> Unit,
    suppressScreenOn: Boolean,
    onSuppressScreenOnChanged: (Boolean) -> Unit,
    suppressWifi: Boolean,
    onSuppressWifiChanged: (Boolean) -> Unit,
    homeWifiSsid: String,
    onHomeWifiSsidChanged: (String) -> Unit,
    suppressWearable: Boolean,
    onSuppressWearableChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Global Settings",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // SECTION 1: On-Device NLP Urgency Classifier
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart NLP Urgency Filter",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Trigger alarms for emergency messages from any sender (non-watchlist)",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Switch(checked = nlpEnabled, onCheckedChange = onNlpEnabledChanged)
                }

                if (nlpEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Urgency Threshold: $nlpThreshold",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = nlpThreshold.toFloat(),
                        onValueChange = { onNlpThresholdChanged(it.toInt()) },
                        valueRange = 10f..90f,
                        steps = 7,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF985EFF),
                            thumbColor = Color(0xFF985EFF)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sensitive (Loud)", color = Color.Gray, fontSize = 11.sp)
                        Text("Strict (Critical Only)", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }

        // SECTION 1.5: Phone Call & Call Interception Overrides
        val notificationManager = remember { context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager }
        var isDndAccessGranted by remember { 
            mutableStateOf(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    notificationManager.isNotificationPolicyAccessGranted
                } else {
                    true
                }
            )
        }

        // Periodically refresh DND access permission check
        LaunchedEffect(Unit) {
            while(true) {
                isDndAccessGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    notificationManager.isNotificationPolicyAccessGranted
                } else {
                    true
                }
                kotlinx.coroutines.delay(1500)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Calls & Silent Overrides",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Configure when ZAlarm should bypass mute, silent, and Do Not Disturb settings.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Divider(color = Color.White.copy(alpha = 0.08f))

                // WhatsApp Call Override
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Override WhatsApp Call Notifications", color = Color.White, fontSize = 14.sp)
                        Text("Triggers loud repeating alarm on incoming WhatsApp calls from VIPs", color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                    Switch(checked = overrideWaCalls, onCheckedChange = onOverrideWaCallsChanged)
                }

                // Phone Call Override
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Override Carrier Phone Calls", color = Color.White, fontSize = 14.sp)
                        Text("Bypasses mute/DND for standard ringing incoming phone calls from VIPs", color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                    Switch(checked = overridePhoneCalls, onCheckedChange = onOverridePhoneCallsChanged)
                }

                if (!isDndAccessGranted) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF3B30).copy(alpha = 0.1f)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "⚠️ DND Policy Access Required",
                                color = Color(0xFFFF453A),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "To successfully unmute your phone's Silent Mode or bypass DND settings, ZAlarm needs DND policy access.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Button(
                                onClick = {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        } catch (e: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Grant DND Access", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // SECTION 2: Presence-Aware Suppression
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Presence-Aware Suppression",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Automatically suppress loud alarms and vibrate instead if you are likely paying attention to your phone or wearable.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Divider(color = Color.White.copy(alpha = 0.08f))

                // Screen Interactive Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Suppress when Screen is On", color = Color.White, fontSize = 14.sp)
                        Text("Active phone use suppresses alarms", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = suppressScreenOn, onCheckedChange = onSuppressScreenOnChanged)
                }

                // Wearable Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Suppress when Smartwatch Connected", color = Color.White, fontSize = 14.sp)
                        Text("Vibe only if paired watch is detected on Bluetooth", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = suppressWearable, onCheckedChange = onSuppressWearableChanged)
                }

                // Wi-Fi Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Suppress on Home Wi-Fi Network", color = Color.White, fontSize = 14.sp)
                        Text("Mute loud alarms when connected to specific SSID", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = suppressWifi, onCheckedChange = onSuppressWifiChanged)
                }

                if (suppressWifi) {
                    OutlinedTextField(
                        value = homeWifiSsid,
                        onValueChange = onHomeWifiSsidChanged,
                        label = { Text("Home Wi-Fi SSID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF985EFF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }

        // SECTION 3: System Access Deep Links
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "System Actions",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Verify Notification Intercept Permission", color = Color.White)
                }

                Button(
                    onClick = {
                        OemBatteryHelper.launchPowerSettings(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Configure OEM Background Autostart", color = Color.White)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

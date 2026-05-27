package com.walarm.app.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.walarm.app.util.OemBatteryHelper
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    
    var isNotificationAccessGranted by remember { mutableStateOf(false) }
    var isBatteryExemptionGranted by remember { mutableStateOf(false) }
    var isPostNotifGranted by remember { mutableStateOf(false) }
    var isStorageGranted by remember { mutableStateOf(false) }
    var isPhonePermissionsGranted by remember { mutableStateOf(false) }
    var isDndAccessGranted by remember { mutableStateOf(false) }

    // Helper functions to check permissions
    fun checkPermissions() {
        // 1. Notification Access
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        isNotificationAccessGranted = enabledListeners?.contains(context.packageName) == true

        // 2. Battery Exemption
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryExemptionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        // 3. Post Notification Permission (Android 13+)
        isPostNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // 4. Storage/Audio Permission (Optional helper for audio pickers)
        isStorageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        // 5. Phone Calls & Contact Lookups
        val phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val callLogGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        isPhonePermissionsGranted = phoneStateGranted && callLogGranted && contactsGranted

        // 6. DND Policy Bypass Access
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        isDndAccessGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }

    // Check periodically or when UI is launched
    LaunchedEffect(Unit) {
        while (true) {
            checkPermissions()
            delay(1000) // check every 1 second when active
        }
    }

    // Permission launchers
    val postNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isPostNotifGranted = granted
    }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isStorageGranted = granted
    }

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        isPhonePermissionsGranted = results.values.all { it }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0C20),
                        Color(0xFF130D2B)
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "🛡️ ZAlarm",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Text(
                text = "Priority WhatsApp Alarms that bypass DND & Mute.",
                fontSize = 16.sp,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // STEP 1 CARD: Notification Access
            PermissionCard(
                title = "1. Notification Intercept",
                description = "Required to scan incoming WhatsApp notifications and identify VIP contacts. Operates locally with 100% privacy.",
                isGranted = isNotificationAccessGranted,
                actionText = "Grant Access",
                onAction = {
                    try {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            )

            // STEP 2 CARD: Battery Optimization
            PermissionCard(
                title = "2. Background Protection",
                description = "Exempt ZAlarm from battery saving so the listener isn't killed in the background.",
                isGranted = isBatteryExemptionGranted,
                actionText = "Disable Saving",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            OemBatteryHelper.launchPowerSettings(context)
                        }
                    }
                }
            )

            // OPPO/OnePlus/Realme HANS Freeze Bypass Card
            val isOplusDevice = remember {
                val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme")
            }
            if (isOplusDevice) {
                var isOplusConfigured by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (isOplusConfigured) Color(0xFF34C759).copy(alpha = 0.5f) else Color(0xFFFFCC00).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.04f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠️ OPPO/OnePlus App Freezing",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isOplusConfigured) Color(0xFF34C759).copy(alpha = 0.2f) else Color(0xFFFFCC00).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = if (isOplusConfigured) " CONFIGURED " else " ATTENTION REQUIRED ",
                                    color = if (isOplusConfigured) Color(0xFF34C759) else Color(0xFFFFCC00),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = "OPPO/OnePlus/Realme devices use an aggressive background freezer (HANS) which suspends the app 20-30 seconds after locking the screen.\n\nTo prevent this, you MUST manually enable background run:\n\n1. Tap 'Configure App Details' below.\n2. Tap 'Battery usage' (or 'Battery').\n3. Enable 'Allow background activity' and 'Allow auto-launch'.",
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isOplusConfigured) {
                                TextButton(
                                    onClick = { isOplusConfigured = true },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("I've Done This", color = Color.LightGray)
                                }
                            }
                            
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "Configure App Details",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // STEP 3 CARD: Post Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    title = "3. Post Notifications",
                    description = "Required to keep the service running reliably with a persistent status notification.",
                    isGranted = isPostNotifGranted,
                    actionText = "Allow Notifications",
                    onAction = {
                        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }

            // STEP 4 CARD: Storage Permission (Optional)
            PermissionCard(
                title = "4. Audio Files Access (Optional)",
                description = "Required to select custom ringtones from your local audio storage files.",
                isGranted = isStorageGranted,
                actionText = "Grant Storage",
                onAction = {
                    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    storageLauncher.launch(permission)
                }
            )

            // STEP 5 CARD: Phone Call Intercept (Optional/Recommended)
            PermissionCard(
                title = "5. Phone Call Intercept (Optional)",
                description = "Required to identify incoming phone calls from your VIP contacts and map them to their names so ZAlarm can override silent mode.",
                isGranted = isPhonePermissionsGranted,
                actionText = "Grant Call Permissions",
                onAction = {
                    phonePermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_CONTACTS
                        )
                    )
                }
            )

            // STEP 6 CARD: DND Policy Bypass (Optional/Recommended)
            PermissionCard(
                title = "6. DND Policy Access (Optional)",
                description = "Allows ZAlarm to programmatically bypass system 'Do Not Disturb' configurations when a priority alert is playing.",
                isGranted = isDndAccessGranted,
                actionText = "Grant DND Access",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            val isMandatoryGranted = isNotificationAccessGranted && isBatteryExemptionGranted && isPostNotifGranted
            
            Button(
                onClick = { if (isMandatoryGranted) onFinished() },
                enabled = isMandatoryGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6200EE),
                    disabledContainerColor = Color.White.copy(alpha = 0.1f)
                )
            ) {
                Text(
                    text = if (isMandatoryGranted) "FINISH SETUP 🚀" else "GRANT MANDATORY PERMISSIONS (1 & 2)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isMandatoryGranted) Color.White else Color.Gray
                )
            }
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isGranted) Color(0xFF34C759).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.04f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isGranted) Color(0xFF34C759).copy(alpha = 0.2f) else Color(0xFFFF3B30).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (isGranted) " ACTIVE " else " INACTIVE ",
                        color = if (isGranted) Color(0xFF34C759) else Color(0xFFFF3B30),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = description,
                color = Color.LightGray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            if (!isGranted) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF985EFF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = actionText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

package com.walarm.app.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.walarm.app.data.AppDatabase
import com.walarm.app.data.WatchedContact
import com.walarm.app.service.ServiceRestartWorker
import com.walarm.app.service.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        database = AppDatabase.getDatabase(this)
        
        // Start Periodic Work Manager Service Watchdog
        scheduleServiceWatchdog()

        setContent {
            var isPermissionsGranted by remember { mutableStateOf(false) }
            val context = LocalContext.current

            // Check permissions in real-time or when app starts
            fun verifyPermissions() {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
                val notifGranted = enabledListeners?.contains(context.packageName) == true
                
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                } else {
                    true
                }
                
                isPermissionsGranted = notifGranted && batteryGranted
            }

            LaunchedEffect(Unit) {
                verifyPermissions()
            }

            // Also check when window gains focus (user returned from settings)
            DisposableEffect(Unit) {
                verifyPermissions()
                onDispose {}
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0C091A),
                    surface = Color(0xFF130D2B),
                    primary = Color(0xFF985EFF),
                    secondary = Color(0xFF007AFF)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0C091A)
                ) {
                    if (isPermissionsGranted) {
                        DashboardScreen(database = database)
                    } else {
                        OnboardingScreen(
                            onFinished = {
                                verifyPermissions()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning to activity
        lifecycleScope.launch {
            val enabledListeners = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
            val notifGranted = enabledListeners?.contains(packageName) == true
            
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(packageName)
            } else {
                true
            }
            
            if (notifGranted && batteryGranted) {
                // Ensure UI updates if permissions were granted
                setContent {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            background = Color(0xFF0C091A),
                            surface = Color(0xFF130D2B),
                            primary = Color(0xFF985EFF),
                            secondary = Color(0xFF007AFF)
                        )
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color(0xFF0C091A)
                        ) {
                            DashboardScreen(database = database)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleServiceWatchdog() {
        val workRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
            15, TimeUnit.MINUTES
        ).build()
        
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "ZAlarmServiceRestartWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

@Composable
fun DashboardScreen(database: AppDatabase) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Read DB Flows
    val contacts by database.contactDao().getAllContactsFlow().collectAsState(initial = emptyList())
    val logs by database.debugLogDao().getRecentLogsFlow().collectAsState(initial = emptyList())

    // Read DataStore Settings
    val nlpEnabled by context.dataStore.data.map { it[booleanPreferencesKey("nlp_enabled")] ?: true }
        .collectAsState(initial = true)
    val nlpThreshold by context.dataStore.data.map { it[intPreferencesKey("nlp_threshold")] ?: 50 }
        .collectAsState(initial = 50)
    val overridePhoneCalls by context.dataStore.data.map { it[booleanPreferencesKey("override_phone_calls")] ?: true }
        .collectAsState(initial = true)
    val overrideWaCalls by context.dataStore.data.map { it[booleanPreferencesKey("override_wa_calls")] ?: true }
        .collectAsState(initial = true)
    val suppressScreenOn by context.dataStore.data.map { it[booleanPreferencesKey("suppress_screen_on")] ?: false }
        .collectAsState(initial = false)
    val suppressWifi by context.dataStore.data.map { it[booleanPreferencesKey("suppress_wifi")] ?: false }
        .collectAsState(initial = false)
    val homeWifiSsid by context.dataStore.data.map { it[stringPreferencesKey("home_wifi_ssid")] ?: "" }
        .collectAsState(initial = "")
    val suppressWearable by context.dataStore.data.map { it[booleanPreferencesKey("suppress_wearable")] ?: false }
        .collectAsState(initial = false)

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color(0xFF130D2B))
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
                            color = Color(0xFF985EFF)
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Watchlist", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Captured Logs", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Settings", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0C091A))
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
                    nlpEnabled = nlpEnabled,
                    onNlpEnabledChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[booleanPreferencesKey("nlp_enabled")] = value }
                        }
                    },
                    nlpThreshold = nlpThreshold,
                    onNlpThresholdChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[intPreferencesKey("nlp_threshold")] = value }
                        }
                    },
                    overridePhoneCalls = overridePhoneCalls,
                    onOverridePhoneCallsChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[booleanPreferencesKey("override_phone_calls")] = value }
                        }
                    },
                    overrideWaCalls = overrideWaCalls,
                    onOverrideWaCallsChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[booleanPreferencesKey("override_wa_calls")] = value }
                        }
                    },
                    suppressScreenOn = suppressScreenOn,
                    onSuppressScreenOnChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[booleanPreferencesKey("suppress_screen_on")] = value }
                        }
                    },
                    suppressWifi = suppressWifi,
                    onSuppressWifiChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[booleanPreferencesKey("suppress_wifi")] = value }
                        }
                    },
                    homeWifiSsid = homeWifiSsid,
                    onHomeWifiSsidChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[stringPreferencesKey("home_wifi_ssid")] = value }
                        }
                    },
                    suppressWearable = suppressWearable,
                    onSuppressWearableChanged = { value ->
                        coroutineScope.launch {
                            context.dataStore.edit { it[booleanPreferencesKey("suppress_wearable")] = value }
                        }
                    }
                )
            }
        }
    }
}

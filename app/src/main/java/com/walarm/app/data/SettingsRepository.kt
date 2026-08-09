package com.walarm.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single process-wide DataStore. `preferencesDataStore` throws if two stores
 * share a name in one process, so this delegate must be declared exactly once.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * An immutable snapshot of every global (non per-contact) user setting.
 *
 * Reading a whole snapshot rather than one key at a time means the alarm pipeline
 * makes a single DataStore read per notification and then decides against a value
 * that cannot change underneath it mid-decision.
 */
data class AppSettings(
    val nlpEnabled: Boolean = true,
    val nlpThreshold: Int = 50,
    val overridePhoneCalls: Boolean = true,
    val overrideWaCalls: Boolean = true,
    val suppressOnScreenOn: Boolean = false,
    val suppressOnHomeWifi: Boolean = false,
    val homeWifiSsid: String = "",
    val suppressOnWearable: Boolean = false
)

/**
 * Typed access to [AppSettings].
 *
 * Key names are declared once here. They intentionally match the strings that were
 * previously scattered across the UI and the services, so existing installs keep
 * their saved preferences.
 */
object SettingsRepository {

    private object Keys {
        val NLP_ENABLED = booleanPreferencesKey("nlp_enabled")
        val NLP_THRESHOLD = intPreferencesKey("nlp_threshold")
        val OVERRIDE_PHONE_CALLS = booleanPreferencesKey("override_phone_calls")
        val OVERRIDE_WA_CALLS = booleanPreferencesKey("override_wa_calls")
        val SUPPRESS_SCREEN_ON = booleanPreferencesKey("suppress_screen_on")
        val SUPPRESS_WIFI = booleanPreferencesKey("suppress_wifi")
        val HOME_WIFI_SSID = stringPreferencesKey("home_wifi_ssid")
        val SUPPRESS_WEARABLE = booleanPreferencesKey("suppress_wearable")
    }

    private val defaults = AppSettings()

    fun flow(context: Context): Flow<AppSettings> =
        context.dataStore.data.map { prefs -> prefs.toSettings() }

    /** Reads the current snapshot. Suspends until DataStore has loaded from disk. */
    suspend fun current(context: Context): AppSettings = flow(context).first()

    private fun Preferences.toSettings() = AppSettings(
        nlpEnabled = this[Keys.NLP_ENABLED] ?: defaults.nlpEnabled,
        nlpThreshold = this[Keys.NLP_THRESHOLD] ?: defaults.nlpThreshold,
        overridePhoneCalls = this[Keys.OVERRIDE_PHONE_CALLS] ?: defaults.overridePhoneCalls,
        overrideWaCalls = this[Keys.OVERRIDE_WA_CALLS] ?: defaults.overrideWaCalls,
        suppressOnScreenOn = this[Keys.SUPPRESS_SCREEN_ON] ?: defaults.suppressOnScreenOn,
        suppressOnHomeWifi = this[Keys.SUPPRESS_WIFI] ?: defaults.suppressOnHomeWifi,
        homeWifiSsid = this[Keys.HOME_WIFI_SSID] ?: defaults.homeWifiSsid,
        suppressOnWearable = this[Keys.SUPPRESS_WEARABLE] ?: defaults.suppressOnWearable
    )

    /** Persists a whole snapshot in one atomic DataStore transaction. */
    suspend fun save(context: Context, settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NLP_ENABLED] = settings.nlpEnabled
            prefs[Keys.NLP_THRESHOLD] = settings.nlpThreshold.coerceIn(0, 100)
            prefs[Keys.OVERRIDE_PHONE_CALLS] = settings.overridePhoneCalls
            prefs[Keys.OVERRIDE_WA_CALLS] = settings.overrideWaCalls
            prefs[Keys.SUPPRESS_SCREEN_ON] = settings.suppressOnScreenOn
            prefs[Keys.SUPPRESS_WIFI] = settings.suppressOnHomeWifi
            prefs[Keys.HOME_WIFI_SSID] = settings.homeWifiSsid
            prefs[Keys.SUPPRESS_WEARABLE] = settings.suppressOnWearable
        }
    }
}

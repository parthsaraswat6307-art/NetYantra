package com.example.netyantra.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "netyantra_settings")

data class AppSettings(
    val checkIntervalSeconds: Long = 30L,
    val speedThresholdPercent: Int = 30,
    val monthlyMobileDataLimitMb: Int = 100,
    val notificationsEnabled: Boolean = true,
    val monthlyMobileUsageBytes: Long = 0L,
    val currentMonthYear: String = ""
) {
    val monthlyMobileUsageMb: Double
        get() = monthlyMobileUsageBytes / (1024.0 * 1024.0)

    val isMonthlyLimitExceeded: Boolean
        get() = monthlyMobileUsageMb >= monthlyMobileDataLimitMb
}

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_CHECK_INTERVAL = longPreferencesKey("key_check_interval")
        val KEY_THRESHOLD_PERCENT = intPreferencesKey("key_threshold_percent")
        val KEY_MONTHLY_LIMIT_MB = intPreferencesKey("key_monthly_limit_mb")
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("key_notifications_enabled")
        val KEY_MONTHLY_USAGE_BYTES = longPreferencesKey("key_monthly_usage_bytes")
        val KEY_CURRENT_MONTH_YEAR = stringPreferencesKey("key_current_month_year")

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val savedMonth = preferences[KEY_CURRENT_MONTH_YEAR] ?: currentMonth
        val usage = if (savedMonth == currentMonth) {
            preferences[KEY_MONTHLY_USAGE_BYTES] ?: 0L
        } else {
            0L // Automatically reset for new month
        }

        AppSettings(
            checkIntervalSeconds = preferences[KEY_CHECK_INTERVAL] ?: 30L,
            speedThresholdPercent = preferences[KEY_THRESHOLD_PERCENT] ?: 30,
            monthlyMobileDataLimitMb = preferences[KEY_MONTHLY_LIMIT_MB] ?: 100,
            notificationsEnabled = preferences[KEY_NOTIFICATIONS_ENABLED] ?: true,
            monthlyMobileUsageBytes = usage,
            currentMonthYear = currentMonth
        )
    }

    suspend fun updateCheckInterval(seconds: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHECK_INTERVAL] = maxOf(5L, seconds)
        }
    }

    suspend fun updateThresholdPercent(percent: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THRESHOLD_PERCENT] = percent.coerceIn(5, 90)
        }
    }

    suspend fun updateMonthlyLimitMb(limitMb: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MONTHLY_LIMIT_MB] = maxOf(10, limitMb)
        }
    }

    suspend fun updateNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun recordMobileUsage(bytesDownloaded: Long) {
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        context.dataStore.edit { prefs ->
            val savedMonth = prefs[KEY_CURRENT_MONTH_YEAR] ?: currentMonth
            val currentUsage = if (savedMonth == currentMonth) {
                prefs[KEY_MONTHLY_USAGE_BYTES] ?: 0L
            } else {
                0L
            }
            prefs[KEY_CURRENT_MONTH_YEAR] = currentMonth
            prefs[KEY_MONTHLY_USAGE_BYTES] = currentUsage + bytesDownloaded
        }
    }
}

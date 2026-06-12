package com.picolink.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "picolink_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val autoConnectOnLaunch: Boolean = false,
    val autoReconnect: Boolean = true,
    val autoTimeSync: Boolean = true,
    val keepScreenOn: Boolean = false,
    val statusPollSeconds: Int = 5,
    val hapticFeedback: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val LAST_ADDR = stringPreferencesKey("last_device_address")
        val LAST_NAME = stringPreferencesKey("last_device_name")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect_on_launch")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val AUTO_TIME_SYNC = booleanPreferencesKey("auto_time_sync")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val POLL_SECONDS = intPreferencesKey("status_poll_seconds")
        val HAPTICS = booleanPreferencesKey("haptic_feedback")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            lastDeviceAddress = p[Keys.LAST_ADDR],
            lastDeviceName = p[Keys.LAST_NAME],
            autoConnectOnLaunch = p[Keys.AUTO_CONNECT] ?: false,
            autoReconnect = p[Keys.AUTO_RECONNECT] ?: true,
            autoTimeSync = p[Keys.AUTO_TIME_SYNC] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
            statusPollSeconds = p[Keys.POLL_SECONDS] ?: 5,
            hapticFeedback = p[Keys.HAPTICS] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.dataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DYNAMIC] = enabled }

    suspend fun setLastDevice(address: String, name: String?) =
        context.dataStore.edit {
            it[Keys.LAST_ADDR] = address
            if (name != null) it[Keys.LAST_NAME] = name else it.remove(Keys.LAST_NAME)
        }

    suspend fun setAutoConnectOnLaunch(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_CONNECT] = enabled }

    suspend fun setAutoReconnect(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }

    suspend fun setAutoTimeSync(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_TIME_SYNC] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) =
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }

    suspend fun setStatusPollSeconds(seconds: Int) =
        context.dataStore.edit { it[Keys.POLL_SECONDS] = seconds }

    suspend fun setHapticFeedback(enabled: Boolean) =
        context.dataStore.edit { it[Keys.HAPTICS] = enabled }
}

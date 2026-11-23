package com.photosync.app.data.prefs

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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 同步偏好（自动同步开关、仅 Wi-Fi、后台同步间隔）。 */
data class SyncPreferences(
    val autoSyncEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES
) {
    companion object {
        /** WorkManager 周期任务的最小间隔为 15 分钟。 */
        const val MIN_INTERVAL_MINUTES = 15
        const val DEFAULT_INTERVAL_MINUTES = 15
        val INTERVAL_OPTIONS = listOf(15, 30, 60)
    }
}

/** 用户偏好：服务器地址、是否允许 HTTP、同步调度等，存放在 DataStore-Preferences。 */
class SettingsRepository(private val context: Context) {

    val serverBaseUrl: Flow<String> = context.settingsDataStore.data.map { it[KEY_SERVER_URL] ?: "" }

    val allowHttp: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_ALLOW_HTTP] ?: false }

    val syncPreferences: Flow<SyncPreferences> = context.settingsDataStore.data.map { prefs ->
        val stored = prefs[KEY_SYNC_INTERVAL] ?: SyncPreferences.DEFAULT_INTERVAL_MINUTES
        SyncPreferences(
            autoSyncEnabled = prefs[KEY_AUTO_SYNC] ?: true,
            wifiOnly = prefs[KEY_WIFI_ONLY] ?: true,
            // 历史版本可能存过已下线的间隔（如 3 小时），回退到当前最大可选间隔。
            intervalMinutes = if (stored in SyncPreferences.INTERVAL_OPTIONS) {
                stored
            } else {
                SyncPreferences.INTERVAL_OPTIONS.max()
            }
        )
    }

    suspend fun getServerBaseUrl(): String = serverBaseUrl.first()

    suspend fun getSyncPreferences(): SyncPreferences = syncPreferences.first()

    suspend fun saveServerConfig(url: String, allowHttpEnabled: Boolean) {
        context.settingsDataStore.edit {
            it[KEY_SERVER_URL] = url.trim().trimEnd('/')
            it[KEY_ALLOW_HTTP] = allowHttpEnabled
        }
    }

    suspend fun saveSyncPreferences(preferences: SyncPreferences) {
        context.settingsDataStore.edit {
            it[KEY_AUTO_SYNC] = preferences.autoSyncEnabled
            it[KEY_WIFI_ONLY] = preferences.wifiOnly
            it[KEY_SYNC_INTERVAL] = preferences.intervalMinutes
                .coerceAtLeast(SyncPreferences.MIN_INTERVAL_MINUTES)
        }
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_base_url")
        val KEY_ALLOW_HTTP = booleanPreferencesKey("allow_http")
        val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEY_SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
    }
}

package com.photosync.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.prefs.SettingsRepository
import com.photosync.app.data.prefs.SyncPreferences
import com.photosync.app.data.repository.AuthRepository
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.ServerRepository
import com.photosync.app.data.repository.SpaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUi(
    val serverUrl: String,
    val email: String,
    val displayName: String,
    val autoSyncEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val intervalMinutes: Int = SyncPreferences.DEFAULT_INTERVAL_MINUTES
)

sealed interface SettingsState {
    data object Loading : SettingsState
    data class Ready(val data: SettingsUi) : SettingsState
    data object LoggedOut : SettingsState
}

/** 设置页 ViewModel：账号/服务器信息、同步调度、退出登录。 */
class SettingsViewModel(
    private val serverRepository: ServerRepository,
    private val tokenStore: SecureTokenStore,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val spaceRepository: SpaceRepository,
    private val settingsRepository: SettingsRepository,
    private val rescheduleSync: suspend () -> Unit,
    private val cancelSync: () -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow<SettingsState>(SettingsState.Loading)
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            settingsRepository.syncPreferences.collect { preferences ->
                val ready = _state.value as? SettingsState.Ready ?: return@collect
                _state.value = SettingsState.Ready(
                    ready.data.copy(
                        autoSyncEnabled = preferences.autoSyncEnabled,
                        wifiOnly = preferences.wifiOnly,
                        intervalMinutes = preferences.intervalMinutes
                    )
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val preferences = settingsRepository.getSyncPreferences()
            _state.value = SettingsState.Ready(
                SettingsUi(
                    serverUrl = serverRepository.getServerBaseUrl().ifBlank { "未配置" },
                    email = tokenStore.userEmail ?: "未登录",
                    displayName = tokenStore.userDisplayName ?: "-",
                    autoSyncEnabled = preferences.autoSyncEnabled,
                    wifiOnly = preferences.wifiOnly,
                    intervalMinutes = preferences.intervalMinutes
                )
            )
        }
    }

    fun setAutoSync(enabled: Boolean) = updatePreferences {
        it.copy(autoSyncEnabled = enabled)
    }

    fun setWifiOnly(enabled: Boolean) = updatePreferences {
        it.copy(wifiOnly = enabled)
    }

    fun setInterval(minutes: Int) = updatePreferences {
        it.copy(intervalMinutes = minutes.coerceAtLeast(SyncPreferences.MIN_INTERVAL_MINUTES))
    }

    private fun updatePreferences(transform: (SyncPreferences) -> SyncPreferences) {
        viewModelScope.launch {
            val current = settingsRepository.getSyncPreferences()
            val next = transform(current)
            if (next != current) {
                settingsRepository.saveSyncPreferences(next)
                rescheduleSync()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            cancelSync()
            authRepository.logout()
            deviceRepository.clearCache()
            spaceRepository.clearCache()
            _state.value = SettingsState.LoggedOut
        }
    }
}

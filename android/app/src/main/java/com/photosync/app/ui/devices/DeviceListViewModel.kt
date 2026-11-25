package com.photosync.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

sealed interface DeviceListUiState {
    data object Loading : DeviceListUiState
    data object Ready : DeviceListUiState
    data class Error(val message: String) : DeviceListUiState
}

/** 设备管理 ViewModel：列表 / 重命名。设备由登录自动登记，不支持手动添加。 */
class DeviceListViewModel(
    private val deviceRepository: DeviceRepository,
    private val deviceName: String
) : ViewModel() {

    /**
     * 展示账号下所有已登录设备（含被主设备移除的设备，状态显示为“被移除”）。
     * 设备主动退出登录后由服务端从列表移除，这里自然不再出现。
     */
    val devices: StateFlow<List<LocalDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _actionInProgress = MutableStateFlow(false)
    val actionInProgress: StateFlow<Boolean> = _actionInProgress.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = DeviceListUiState.Loading
            // 本机未登记或凭据被吊销时自动补登记。
            deviceRepository.ensureThisDevice(deviceName)
            when (val result = deviceRepository.refreshDevices()) {
                is ApiResult.Success -> _uiState.value = DeviceListUiState.Ready
                is ApiResult.Failure -> {
                    _uiState.value = if (devices.value.isEmpty()) {
                        DeviceListUiState.Error(result.message)
                    } else {
                        DeviceListUiState.Ready
                    }
                    _messages.emit(result.message)
                }
            }
        }
    }

    fun rename(deviceId: String, newName: String) {
        val name = newName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = deviceRepository.renameDevice(deviceId, name)) {
                is ApiResult.Success -> _messages.emit("设备已重命名为「$name」")
                is ApiResult.Failure -> _messages.emit("重命名失败：${result.message}")
            }
        }
    }

    /** 本机抢占主设备身份（账号没有主设备时才允许）。 */
    fun claimPrimary() {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.claimPrimaryDevice()) {
                is ApiResult.Success -> _messages.emit("本机已设为主设备")
                is ApiResult.Failure -> {
                    // 可能是别的设备刚抢占了主设备，刷新以同步最新状态
                    deviceRepository.refreshDevices()
                    _messages.emit(
                        if (result.code == "PRIMARY_DEVICE_TAKEN") {
                            "当前主设备在线，无法抢占；该设备离线超过 5 分钟后可再次尝试"
                        } else {
                            "设置主设备失败：${result.message}"
                        }
                    )
                }
            }
            _actionInProgress.value = false
        }
    }

    /** 主设备主动放弃身份，之后其它设备可以重新抢占。 */
    fun releasePrimary() {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.releasePrimaryDevice()) {
                is ApiResult.Success -> _messages.emit("已放弃主设备身份")
                is ApiResult.Failure -> _messages.emit("放弃主设备失败：${result.message}")
            }
            _actionInProgress.value = false
        }
    }

    /** 被移除的本机发起重新加入申请。 */
    fun requestRejoin() {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.requestRejoin()) {
                is ApiResult.Success -> _messages.emit("已提交重新加入申请，等待主设备同意")
                is ApiResult.Failure -> {
                    deviceRepository.refreshDevices()
                    _messages.emit("申请重新加入失败：${result.message}")
                }
            }
            _actionInProgress.value = false
        }
    }

    /** 主设备同意被移除设备重新加入。 */
    fun approveRejoin(deviceId: String) {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.approveRejoin(deviceId)) {
                is ApiResult.Success -> _messages.emit("已同意该设备重新加入")
                is ApiResult.Failure -> {
                    deviceRepository.refreshDevices()
                    _messages.emit(
                        if (result.code == "DEVICE_NOT_PRIMARY") {
                            "只有主设备才能同意重新加入"
                        } else {
                            "同意失败：${result.message}"
                        }
                    )
                }
            }
            _actionInProgress.value = false
        }
    }

    /** 主设备拒绝被移除设备的重新加入申请。 */
    fun rejectRejoin(deviceId: String) {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.rejectRejoin(deviceId)) {
                is ApiResult.Success -> _messages.emit("已拒绝该设备的重新加入申请")
                is ApiResult.Failure -> {
                    deviceRepository.refreshDevices()
                    _messages.emit(
                        if (result.code == "DEVICE_NOT_PRIMARY") {
                            "只有主设备才能拒绝重新加入"
                        } else {
                            "拒绝失败：${result.message}"
                        }
                    )
                }
            }
            _actionInProgress.value = false
        }
    }

    /** 主设备把已移除设备从列表清理（记录保留，该设备重新申请时会再次出现）。 */
    fun purgeRemovedDevice(deviceId: String) {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.purgeRemovedDevice(deviceId)) {
                is ApiResult.Success -> _messages.emit("已从列表清理该设备")
                is ApiResult.Failure -> {
                    deviceRepository.refreshDevices()
                    _messages.emit(
                        if (result.code == "DEVICE_NOT_PRIMARY") {
                            "只有主设备才能清理设备列表"
                        } else {
                            "清理失败：${result.message}"
                        }
                    )
                }
            }
            _actionInProgress.value = false
        }
    }

    /** 主设备移除从设备。 */
    fun removeDevice(deviceId: String) {
        if (_actionInProgress.value) return
        viewModelScope.launch {
            _actionInProgress.value = true
            when (val result = deviceRepository.removeDevice(deviceId)) {
                is ApiResult.Success -> _messages.emit("已移除从设备")
                is ApiResult.Failure -> {
                    deviceRepository.refreshDevices()
                    _messages.emit(
                        if (result.code == "DEVICE_NOT_PRIMARY") {
                            "只有主设备才能移除其它设备"
                        } else {
                            "移除失败：${result.message}"
                        }
                    )
                }
            }
            _actionInProgress.value = false
        }
    }
}

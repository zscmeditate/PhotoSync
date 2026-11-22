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

/** 设备管理 ViewModel：列表 / 重命名 / 移除 / 添加（注册本机）。 */
class DeviceListViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val devices: StateFlow<List<LocalDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = DeviceListUiState.Loading
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

    fun remove(deviceId: String) {
        viewModelScope.launch {
            when (val result = deviceRepository.removeDevice(deviceId)) {
                is ApiResult.Success -> _messages.emit("设备已移除，其本地文件不会被删除")
                is ApiResult.Failure -> _messages.emit("移除失败：${result.message}")
            }
        }
    }

    fun addThisDevice(deviceName: String) {
        viewModelScope.launch {
            when (val result = deviceRepository.registerThisDevice(deviceName)) {
                is ApiResult.Success -> _messages.emit("本机设备已注册")
                is ApiResult.Failure -> _messages.emit("注册本机失败：${result.message}")
            }
        }
    }
}

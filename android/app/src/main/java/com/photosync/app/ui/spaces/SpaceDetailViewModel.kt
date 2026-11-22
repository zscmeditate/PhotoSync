package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingFileCount
import com.photosync.app.data.local.SyncTaskStats
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.data.repository.SyncRepository
import com.photosync.app.data.sync.FolderScanProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SpaceDetailViewModel(
    private val spaceRepository: SpaceRepository,
    private val deviceRepository: DeviceRepository,
    private val syncRepository: SyncRepository,
    private val scheduleSync: () -> Unit,
    private val spaceId: String
) : ViewModel() {
    val currentDeviceId: String? = spaceRepository.currentDeviceId()

    val space: StateFlow<LocalSpace?> = spaceRepository.observeSpace(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mappings: StateFlow<List<LocalFolderMapping>> = spaceRepository.observeMappings(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fileCounts: StateFlow<List<MappingFileCount>> = spaceRepository.observeFileCounts(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStats: StateFlow<SyncTaskStats> = syncRepository.observeStats(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncTaskStats())

    val devices: StateFlow<List<LocalDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _members = MutableStateFlow<List<SpaceMemberDto>>(emptyList())
    val members: StateFlow<List<SpaceMemberDto>> = _members.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _membershipChanging = MutableStateFlow<String?>(null)
    val membershipChanging: StateFlow<String?> = _membershipChanging.asStateFlow()

    private val _scanningIds = MutableStateFlow<Set<String>>(emptySet())
    val scanningIds: StateFlow<Set<String>> = _scanningIds.asStateFlow()

    private val _scanProgress = MutableStateFlow<Map<String, FolderScanProgress>>(emptyMap())
    val scanProgress: StateFlow<Map<String, FolderScanProgress>> = _scanProgress.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            loadRemote()
            deviceRepository.refreshDevices()
            _loading.value = false
        }
    }

    private suspend fun loadRemote() {
        when (val result = spaceRepository.refreshSpace(spaceId)) {
            is ApiResult.Success -> _members.value = result.data
            is ApiResult.Failure -> _messages.emit(result.message)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            syncRepository.requeueFailed(spaceId)
            scheduleSync()
            _messages.emit("已加入联网同步队列；冲突文件不会被覆盖")
        }
    }

    fun rescan(mappingId: String) {
        if (_scanningIds.value.contains(mappingId)) return
        viewModelScope.launch {
            _scanningIds.value = _scanningIds.value + mappingId
            _scanProgress.value = _scanProgress.value + (mappingId to FolderScanProgress(0, ""))
            when (val result = spaceRepository.scanMapping(mappingId) { progress ->
                _scanProgress.value = _scanProgress.value + (mappingId to progress)
            }) {
                is ApiResult.Success -> {
                    val preview = result.data
                    _messages.emit(
                        "扫描完成：将上传 ${preview.summary.toUpload} / 下载 ${preview.summary.toDownload} / 冲突 ${preview.summary.conflictCount}"
                    )
                    if (preview.truncated) _messages.emit("清单过长已截断，仅上报前 10000 条")
                }
                is ApiResult.Failure -> {
                    val prefix = if (result.code == "NETWORK_ERROR") "本地扫描已完成，差异预览失败" else "扫描失败"
                    _messages.emit("$prefix：${result.message}")
                }
            }
            _scanningIds.value = _scanningIds.value - mappingId
            _scanProgress.value = _scanProgress.value - mappingId
        }
    }

    fun updateMapping(
        mapping: LocalFolderMapping,
        remotePrefix: String,
        includeSubdirs: Boolean,
        onlyMedia: Boolean
    ) {
        viewModelScope.launch {
            when (val result = spaceRepository.updateMapping(mapping, remotePrefix, includeSubdirs, onlyMedia)) {
                is ApiResult.Success -> _messages.emit("目录映射已更新，可重新扫描生成最新预览")
                is ApiResult.Failure -> {
                    val message = if (result.code == "MAPPING_OVERLAP") {
                        "远端路径与同空间的本机已有映射重叠"
                    } else {
                        result.message
                    }
                    _messages.emit("更新失败：$message")
                }
            }
        }
    }

    fun deleteMapping(mappingId: String) {
        viewModelScope.launch {
            when (val result = spaceRepository.deleteMapping(mappingId)) {
                is ApiResult.Success -> _messages.emit("目录映射已删除，本地文件未受影响")
                is ApiResult.Failure -> _messages.emit("删除映射失败：${result.message}")
            }
        }
    }

    fun setMembership(deviceId: String, join: Boolean) {
        if (_membershipChanging.value != null) return
        viewModelScope.launch {
            _membershipChanging.value = deviceId
            val result = if (join) {
                spaceRepository.addMember(spaceId, deviceId)
            } else {
                spaceRepository.removeMember(spaceId, deviceId)
            }
            when (result) {
                is ApiResult.Success -> {
                    _messages.emit(if (join) "设备已加入空间" else "设备已移出空间")
                    loadRemote()
                }
                is ApiResult.Failure -> _messages.emit("成员变更失败：${result.message}")
            }
            _membershipChanging.value = null
        }
    }
}

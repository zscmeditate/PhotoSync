package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingFileCount
import com.photosync.app.data.local.SyncTaskStats
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.JoinRequestDto
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    /** 已选好本地目录、等待主设备同意加入空间。 */
    val bindingPending: StateFlow<Boolean> = spaceRepository.observePendingBinding(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val fileCounts: StateFlow<List<MappingFileCount>> = spaceRepository.observeFileCounts(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStats: StateFlow<SyncTaskStats> = syncRepository.observeStats(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncTaskStats())

    val devices: StateFlow<List<LocalDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 只有主设备可以管理空间成员；从设备只能退出空间 */
    val canManageMembers: StateFlow<Boolean> = deviceRepository.devices
        .map { list -> list.any { it.isThisDevice && it.isPrimary } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _members = MutableStateFlow<List<SpaceMemberDto>>(emptyList())
    val members: StateFlow<List<SpaceMemberDto>> = _members.asStateFlow()

    /** 主设备看到的待审批加入申请。 */
    private val _joinRequests = MutableStateFlow<List<JoinRequestDto>>(emptyList())
    val joinRequests: StateFlow<List<JoinRequestDto>> = _joinRequests.asStateFlow()

    /** 正在同意/拒绝的申请，防止重复提交。 */
    private val _resolvingRequestId = MutableStateFlow<String?>(null)
    val resolvingRequestId: StateFlow<String?> = _resolvingRequestId.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _membershipChanging = MutableStateFlow<String?>(null)
    val membershipChanging: StateFlow<String?> = _membershipChanging.asStateFlow()

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
            finishPendingBindingIfNeeded()
            loadJoinRequestsIfPrimary()
            _loading.value = false
        }
    }

    private suspend fun loadRemote() {
        when (val result = spaceRepository.refreshSpace(spaceId)) {
            is ApiResult.Success -> _members.value = result.data
            is ApiResult.Failure -> _messages.emit(result.message)
        }
    }

    /** 只有主设备需要加载待审批的加入申请。 */
    private suspend fun loadJoinRequestsIfPrimary() {
        if (!deviceRepository.isThisDevicePrimary.first()) {
            _joinRequests.value = emptyList()
            return
        }
        when (val result = spaceRepository.loadJoinRequests(spaceId)) {
            is ApiResult.Success -> _joinRequests.value = result.data
            // 主设备刷新失败时不打断当前页面，保留已有申请列表。
            is ApiResult.Failure -> Unit
        }
    }

    /** 审批通过后自动用暂存目录建立映射，并立即扫描一次。 */
    private suspend fun finishPendingBindingIfNeeded() {
        when (val result = spaceRepository.finishPendingBinding(spaceId)) {
            is ApiResult.Success -> result.data?.let { mapping ->
                _messages.emit("已加入空间，目录已绑定")
                spaceRepository.scanMapping(mapping.id)
                scheduleSync()
            }
            is ApiResult.Failure -> _messages.emit("完成目录绑定失败：${result.message}")
        }
    }

    /** 主设备同意或拒绝加入申请。 */
    fun resolveJoinRequest(requestId: String, approve: Boolean) {
        if (_resolvingRequestId.value != null) return
        viewModelScope.launch {
            _resolvingRequestId.value = requestId
            when (val result = spaceRepository.resolveJoinRequest(requestId, approve)) {
                is ApiResult.Success -> {
                    _messages.emit(
                        if (approve) {
                            "${result.data.deviceName} 已加入空间，可开始绑定目录"
                        } else {
                            "已拒绝 ${result.data.deviceName} 的加入申请"
                        }
                    )
                    loadRemote()
                    loadJoinRequestsIfPrimary()
                }
                is ApiResult.Failure -> _messages.emit("处理加入申请失败：${result.message}")
            }
            _resolvingRequestId.value = null
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            syncRepository.requeueFailed(spaceId)
            scheduleSync()
            _messages.emit("已加入联网同步队列；冲突文件不会被覆盖")
        }
    }

    fun showMessage(message: String) {
        viewModelScope.launch { _messages.emit(message) }
    }

    /** 更改本地目录绑定；空间必须始终绑定一个目录，因此只允许替换。 */
    fun changeDirectory(mapping: LocalFolderMapping, treeUri: String, localPathLabel: String) {
        viewModelScope.launch {
            when (val result = spaceRepository.changeMappingDirectory(mapping, treeUri, localPathLabel)) {
                is ApiResult.Success -> {
                    _messages.emit("本地目录已更改，正在重新扫描并同步")
                    scheduleSync()
                }
                is ApiResult.Failure -> _messages.emit("更改目录失败：${result.message}")
            }
        }
    }

    /** 从设备退出空间：移出本机设备。 */
    fun exitSpace() {
        val deviceId = currentDeviceId ?: return
        setMembership(deviceId, false)
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
                    _messages.emit(
                        if (join) "设备已加入空间，请绑定本地目录"
                        else "已退出空间，本机目录绑定已解除；可在「加入空间」中重新加入"
                    )
                    loadRemote()
                }
                is ApiResult.Failure -> _messages.emit("成员变更失败：${result.message}")
            }
            _membershipChanging.value = null
        }
    }
}

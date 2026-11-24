package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.SpaceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SpaceListViewModel(
    private val spaceRepository: SpaceRepository,
    private val deviceRepository: DeviceRepository
) : ViewModel() {
    val spaces: StateFlow<List<LocalSpace>> = spaceRepository.spaces
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 只有主设备可以创建空间；从设备只能加入或退出已有空间 */
    val canCreateSpace: StateFlow<Boolean> = deviceRepository.isThisDevicePrimary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val mappings: StateFlow<List<LocalFolderMapping>> = spaceRepository.observeMappingsForCurrentDevice()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val currentDeviceId: String? = spaceRepository.currentDeviceId()

    val devices: StateFlow<List<LocalDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 只有主设备可以管理空间成员；从设备只能退出空间 */
    val canManageMembers: StateFlow<Boolean> = deviceRepository.devices
        .map { list -> list.any { it.isThisDevice && it.isPrimary } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 各空间参与设备（空间卡片内展示）；刷新空间时一并加载。 */
    private val _membersBySpace = MutableStateFlow<Map<String, List<SpaceMemberDto>>>(emptyMap())
    val membersBySpace: StateFlow<Map<String, List<SpaceMemberDto>>> = _membersBySpace.asStateFlow()

    /** 正在加入/移出的设备，防止重复提交。 */
    private val _membershipChanging = MutableStateFlow<String?>(null)
    val membershipChanging: StateFlow<String?> = _membershipChanging.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            when (val result = spaceRepository.refreshSpaces()) {
                is ApiResult.Success -> _loadError.value = null
                is ApiResult.Failure -> {
                    _loadError.value = result.message
                    _messages.emit(result.message)
                }
            }
            loadMembersOfSpaces()
            // 参与设备管理弹层需要本账号完整设备列表
            deviceRepository.refreshDevices()
            _refreshing.value = false
        }
    }

    /** 并发加载每个空间的参与设备；单个空间失败不影响其他空间展示。 */
    private suspend fun loadMembersOfSpaces() {
        // 从数据库取空间 ID：spaces 是 WhileSubscribed 的 StateFlow，此时可能还没有订阅者
        val spaceIds = spaceRepository.getAllSpaces()
            .filter { it.role != "none" }
            .map { it.id }
        val loaded = coroutineScope {
            spaceIds.map { spaceId ->
                async {
                    val result = spaceRepository.loadMembers(spaceId)
                    spaceId to (result as? ApiResult.Success)?.data.orEmpty()
                }
            }.awaitAll()
        }
        _membersBySpace.value = loaded.toMap()
    }

    /** 空间卡片内「管理」：勾选加入或移出设备。 */
    fun setMembership(spaceId: String, deviceId: String, join: Boolean) {
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
                    val deviceName = devices.value.firstOrNull { it.id == deviceId }?.deviceName
                    val isSelf = deviceId == currentDeviceId
                    _messages.emit(
                        when {
                            join && isSelf -> "本机已加入空间，请进入空间绑定本地目录"
                            join -> "已将「${deviceName ?: "该设备"}」加入空间"
                            isSelf -> "已退出空间，本机目录绑定已解除"
                            else -> "已将「${deviceName ?: "该设备"}」移出空间，其目录绑定已解除"
                        }
                    )
                    val refreshed = spaceRepository.loadMembers(spaceId)
                    if (refreshed is ApiResult.Success) {
                        _membersBySpace.value = _membersBySpace.value + (spaceId to refreshed.data)
                    }
                }
                is ApiResult.Failure -> _messages.emit("成员变更失败：${result.message}")
            }
            _membershipChanging.value = null
        }
    }

    fun deleteSpace(spaceId: String) {
        viewModelScope.launch {
            when (val result = spaceRepository.deleteSpace(spaceId)) {
                is ApiResult.Success -> _messages.emit("已删除服务器上的整个空间")
                is ApiResult.Failure -> _messages.emit("删除失败：${result.message}")
            }
        }
    }

    fun hasAbnormalMapping(spaceId: String): Boolean = mappings.value.any {
        it.spaceId == spaceId && (it.status == MappingStatus.ERROR || it.status == MappingStatus.ACCESS_REVOKED)
    }
}

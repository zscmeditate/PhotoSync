package com.photosync.app.ui.spaces

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.SpaceRepository
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

    /** 本机尚未加入（role=none）的空间：同步空间列表同样展示，进入详情后可申请加入并等待审批 */
    val pendingSpaces: StateFlow<List<LocalSpace>> = spaceRepository.allSpaces
        .map { list -> list.filter { it.role == "none" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 只有主设备可以创建空间；从设备只能加入或退出已有空间 */
    val canCreateSpace: StateFlow<Boolean> = deviceRepository.isThisDevicePrimary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val mappings: StateFlow<List<LocalFolderMapping>> = spaceRepository.observeMappingsForCurrentDevice()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 本机各空间的文件数量，空间卡片名称后展示。 */
    val spaceFileCounts: StateFlow<Map<String, Int>> = spaceRepository.observeSpaceFileCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 上次从服务端拉空间列表的时间戳（System.currentTimeMillis）。 */
    @Volatile
    var lastRefreshAt: Long = 0L
        private set

    init {
        refresh()
    }

    /** 强制刷新；默认 30 秒内不重复请求（手动或切 Tab 触发）。 */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            lastRefreshAt = System.currentTimeMillis()
            when (val result = spaceRepository.refreshSpaces()) {
                is ApiResult.Success -> _loadError.value = null
                is ApiResult.Failure -> {
                    _loadError.value = result.message
                    _messages.emit(result.message)
                }
            }
            // 刷新本机主从状态，控制创建空间入口
            deviceRepository.refreshDevices()
            _refreshing.value = false
        }
    }

    companion object {
        /** 切 Tab 自动刷新的最小间隔，避免频繁请求。 */
        private const val REFRESH_INTERVAL_MS = 30_000L
    }

    fun deleteSpace(spaceId: String) {
        viewModelScope.launch {
            when (val result = spaceRepository.deleteSpace(spaceId)) {
                is ApiResult.Success -> _messages.emit("已删除服务器上的整个空间")
                is ApiResult.Failure -> _messages.emit("删除失败：${result.message}")
            }
        }
    }

    /** 管理员用选中的照片设置空间头像：压缩后上传服务端，所有设备刷新后同步展示。 */
    fun setSpaceCover(spaceId: String, uri: Uri) {
        viewModelScope.launch {
            when (val result = spaceRepository.uploadSpaceCover(spaceId, uri)) {
                is ApiResult.Success -> _messages.emit("已更新空间头像")
                is ApiResult.Failure -> _messages.emit("设置头像失败：${result.message}")
            }
        }
    }

    /** 管理员移除空间头像，所有设备恢复默认的文件夹图标。 */
    fun clearSpaceCover(spaceId: String) {
        viewModelScope.launch {
            when (val result = spaceRepository.removeSpaceCover(spaceId)) {
                is ApiResult.Success -> _messages.emit("已移除空间头像")
                is ApiResult.Failure -> _messages.emit("移除头像失败：${result.message}")
            }
        }
    }

    fun hasAbnormalMapping(spaceId: String): Boolean = mappings.value.any {
        it.spaceId == spaceId && (it.status == MappingStatus.ERROR || it.status == MappingStatus.ACCESS_REVOKED)
    }

    /** 立即同步所有空间：由 UI 层传入 SyncScheduler.enqueue 作为 lambda，ViewModel 不依赖 Context。 */
    fun syncAll(scheduler: () -> Unit) {
        viewModelScope.launch {
            scheduler()
            _messages.emit("已排队同步所有空间")
        }
    }
}

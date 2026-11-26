package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.MappingFileCount
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.local.SyncTaskStats
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.JoinRequestDto
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.remote.ApiClient
import com.photosync.app.data.repository.DeviceRepository
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.data.repository.SyncRepository
import com.photosync.app.data.sync.FileFailureInfo
import com.photosync.app.data.sync.SyncEngine
import com.photosync.app.data.sync.SyncRunResult
import kotlinx.coroutines.CancellationException
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
    private val syncEngine: SyncEngine,
    private val apiClient: ApiClient,
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

    /** 本地批量删除待确认数量：超过保护阈值时云端暂不删除，也不会把文件重新下载回本地。 */
    val pendingDeletionCount: StateFlow<Int> = syncRepository.observePendingDeletionCount(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _resolvingBulkDelete = MutableStateFlow(false)
    val resolvingBulkDelete: StateFlow<Boolean> = _resolvingBulkDelete.asStateFlow()

    /** 确认批量删除：把待确认记录转成删除任务并立即执行。 */
    fun confirmPendingDeletions() {
        if (_resolvingBulkDelete.value) return
        viewModelScope.launch {
            _resolvingBulkDelete.value = true
            try {
                syncRepository.confirmPendingDeletions(spaceId)
                val result = syncEngine.runQueuedTasks()
                val msg = when (result) {
                    SyncRunResult.SUCCESS -> "已同步删除云端文件"
                    SyncRunResult.RETRY -> "删除已提交，等待下一次同步"
                    SyncRunResult.FAILURE -> "部分删除任务失败，请查看任务详情"
                }
                _messages.emit(msg)
            } finally {
                _resolvingBulkDelete.value = false
            }
        }
    }

    /** 保留云端文件：放弃这批待确认删除，下次同步会把它们重新下载回本地目录。 */
    fun keepPendingDeletionFiles() {
        if (_resolvingBulkDelete.value) return
        viewModelScope.launch {
            _resolvingBulkDelete.value = true
            try {
                syncRepository.cancelPendingDeletions(spaceId)
                scheduleSync()
                _messages.emit("已保留云端文件，稍后会重新下载回本地")
            } finally {
                _resolvingBulkDelete.value = false
            }
        }
    }

    val syncStats: StateFlow<SyncTaskStats> = syncRepository.observeStats(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncTaskStats())

    /** 该空间的最近同步任务（含已完成，供筛选器展示），按空间隔离。 */
    val tasks: StateFlow<List<LocalSyncTask>> = syncRepository.observeRecentTasks(spaceId, 200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 正在处理中的任务，防止重复提交。 */
    private val _resolvingTaskId = MutableStateFlow<String?>(null)
    val resolvingTaskId: StateFlow<String?> = _resolvingTaskId.asStateFlow()

    /** 手动同步进行中（含扫描阶段），按钮据此显示「正在同步…」并禁用，避免点击后无反馈。 */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** 后台同步（启动/周期/列表页）是否正在同步当前空间，用于让按钮状态与真实扫描保持一致。 */
    val backgroundSyncing: StateFlow<Boolean> = syncEngine.syncingSpaceIds
        .map { spaceId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 正在批量按云端优先处理重名失败任务。 */
    private val _resolvingAllDuplicateNames = MutableStateFlow(false)
    val resolvingAllDuplicateNames: StateFlow<Boolean> = _resolvingAllDuplicateNames.asStateFlow()

    /** 任务列表失败详情弹层状态：点击失败任务的「处理」后加载云端/本地文件信息。 */
    private val _taskFailureDetail = MutableStateFlow<FailureDetailUi?>(null)
    val taskFailureDetail: StateFlow<FailureDetailUi?> = _taskFailureDetail.asStateFlow()

    /** 当前处理面板对应的任务（供面板内的重试/取消操作与按钮可用性判断）。 */
    private val _detailTask = MutableStateFlow<LocalSyncTask?>(null)
    val detailTask: StateFlow<LocalSyncTask?> = _detailTask.asStateFlow()

    fun openTaskFailureDetail(task: LocalSyncTask) {
        if (_taskFailureDetail.value?.loading == true) return
        _detailTask.value = task
        viewModelScope.launch {
            val index = syncEngine.taskFailureIndex(task)
            _taskFailureDetail.value = FailureDetailUi(file = index, loading = true)
            val info = syncEngine.fileFailureInfo(index)
            // 云端与本地都已不存在（典型：远端删除后遗留的下载失败记录），
            // 没有可处理的对象，直接清理，避免打开后只有“两边都不存在”的死路面板。
            if (isObsoleteFailure(task, info)) {
                syncRepository.cancelTask(task.id)
                closeTaskFailureDetail()
                _messages.emit("云端与本地文件均已不存在，已清理该失败记录")
                return@launch
            }
            _taskFailureDetail.value = FailureDetailUi(
                file = index,
                loading = false,
                info = info
            )
        }
    }

    private fun isObsoleteFailure(task: LocalSyncTask, info: FileFailureInfo): Boolean {
        if (task.state != SyncTaskState.FAILED) return false
        if (info.cloud != null || info.localSize != null) return false
        val code = task.error?.substringBefore('：')?.trim()
        return code in OBSOLETE_FAILURE_CODES
    }

    private companion object {
        /** 远端文件/对象已消失导致的失败码，配合“本地也不存在”判定为过期失败记录。 */
        val OBSOLETE_FAILURE_CODES = setOf("OBJECT_NOT_FOUND", "FILE_NOT_FOUND", "FILE_IS_DELETED")
    }

    fun closeTaskFailureDetail() {
        _detailTask.value = null
        _taskFailureDetail.value = null
    }

    /** 处理面板内重试当前任务。 */
    fun retryDetailTask() {
        val task = _detailTask.value ?: return
        viewModelScope.launch {
            syncRepository.retryTask(task.id)
            closeTaskFailureDetail()
            scheduleSync()
            _messages.emit("已重新排队：${task.remoteRelativePath.substringAfterLast('/')}")
        }
    }

    /** 处理面板内取消当前任务。 */
    fun cancelDetailTask() {
        val task = _detailTask.value ?: return
        viewModelScope.launch {
            syncRepository.cancelTask(task.id)
            closeTaskFailureDetail()
            _messages.emit("已取消：${task.remoteRelativePath.substringAfterLast('/')}")
        }
    }

    /** 任务失败处理：保留本地 / 保留云端 / 两端都删除，成功后收尾旧失败任务。 */
    fun resolveTaskFile(action: FileResolveAction) {
        if (_resolvingTaskId.value != null) return
        val task = _detailTask.value ?: return
        viewModelScope.launch {
            _resolvingTaskId.value = task.id
            val index = syncEngine.taskFailureIndex(task)
            val error = when (action) {
                FileResolveAction.KEEP_LOCAL -> syncEngine.resolveFileKeepLocal(index)
                FileResolveAction.KEEP_CLOUD -> syncEngine.resolveFileKeepCloud(index)
                FileResolveAction.DELETE_BOTH -> syncEngine.resolveFileDeleteBoth(index)
            }
            _resolvingTaskId.value = null
            if (error == null) {
                syncRepository.cancelTask(task.id)
                closeTaskFailureDetail()
                scheduleSync()
                _messages.emit(
                    when (action) {
                        FileResolveAction.KEEP_LOCAL -> "已保留本地版本，正在覆盖云端"
                        FileResolveAction.KEEP_CLOUD -> "已保留云端版本，将重新下载"
                        FileResolveAction.DELETE_BOTH -> "已删除本地与云端文件"
                    }
                )
            } else {
                _messages.emit("处理失败：$error")
            }
        }
    }

    /** 批量处理所有重名失败任务：保留云端版本并重新下载，其他失败类型不受影响。 */
    fun resolveAllDuplicateNamesKeepCloud() {
        if (_resolvingTaskId.value != null || _resolvingAllDuplicateNames.value) return
        val duplicateTasks = tasks.value
            .filter {
                it.state == SyncTaskState.FAILED &&
                    it.error?.substringBefore('：') == "FILE_ALREADY_EXISTS"
            }
            .distinctBy { it.mappingId to it.localRelativePath }
        if (duplicateTasks.isEmpty()) {
            showMessage("没有重名失败任务")
            return
        }
        viewModelScope.launch {
            _resolvingAllDuplicateNames.value = true
            var resolved = 0
            var failed = 0
            try {
                // 批量「保留云端」：每个映射一次 scanManifest 拉远端清单，其余全本地处理，
                // 不再逐条 getFileByPath/getFileVersions 往返后端
                val indexes = duplicateTasks.map { syncEngine.taskFailureIndex(it) }
                val errors = syncEngine.resolveKeepCloudAll(indexes)
                duplicateTasks.forEachIndexed { index, task ->
                    if (errors[index] == null) {
                        syncRepository.cancelTask(task.id)
                        resolved++
                    } else {
                        failed++
                    }
                }
            } finally {
                _resolvingAllDuplicateNames.value = false
            }
            if (resolved > 0) scheduleSync()
            _messages.emit(
                if (failed == 0) {
                    "已将 $resolved 个重名任务设为云端优先，将重新下载"
                } else {
                    "已处理 $resolved 个重名任务，$failed 个处理失败"
                }
            )
        }
    }

    /** 批量处理所有失败任务：本地与云端比较体积，保留更大的一方。 */
    fun resolveAllLargerWins() {
        if (_resolvingTaskId.value != null || _resolvingAllDuplicateNames.value) return
        val failedTasks = tasks.value
            .filter { it.state == SyncTaskState.FAILED }
            .distinctBy { it.mappingId to it.localRelativePath }
        if (failedTasks.isEmpty()) {
            showMessage("没有失败任务")
            return
        }
        viewModelScope.launch {
            _resolvingAllDuplicateNames.value = true
            var resolved = 0
            var failed = 0
            try {
                val indexes = failedTasks.map { syncEngine.taskFailureIndex(it) }
                val errors = syncEngine.resolveLargerWinsAll(indexes)
                failedTasks.forEachIndexed { index, task ->
                    if (errors[index] == null) {
                        syncRepository.cancelTask(task.id)
                        resolved++
                    } else {
                        failed++
                    }
                }
            } finally {
                _resolvingAllDuplicateNames.value = false
            }
            if (resolved > 0) scheduleSync()
            _messages.emit(
                if (failed == 0) {
                    "已将 $resolved 个失败任务设为较大文件优先"
                } else {
                    "已处理 $resolved 个失败任务，$failed 个处理失败"
                }
            )
        }
    }

    /** 云端存储统计（「本地目录」右侧「云端」按钮展示用）。 */
    private val _storageStats = MutableStateFlow<StorageStatsUiState?>(null)
    val storageStats: StateFlow<StorageStatsUiState?> = _storageStats.asStateFlow()

    /** 只有主设备可以审批加入申请与管理参与设备。 */
    val canManageMembers: StateFlow<Boolean> = deviceRepository.devices
        .map { list -> list.any { it.isThisDevice && it.isPrimary } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 当前空间的参与设备，在空间详情 Tab 中展示。 */
    private val _members = MutableStateFlow<List<SpaceMemberDto>>(emptyList())
    val members: StateFlow<List<SpaceMemberDto>> = _members.asStateFlow()

    /** 参与设备管理弹层需要本账号完整设备列表。 */
    val devices: StateFlow<List<LocalDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 正在加入/移出的设备，防止重复提交。 */
    private val _membershipChanging = MutableStateFlow<String?>(null)
    val membershipChanging: StateFlow<String?> = _membershipChanging.asStateFlow()

    /** 主设备看到的待审批加入申请。 */
    private val _joinRequests = MutableStateFlow<List<JoinRequestDto>>(emptyList())
    val joinRequests: StateFlow<List<JoinRequestDto>> = _joinRequests.asStateFlow()

    /** 正在同意/拒绝的申请，防止重复提交。 */
    private val _resolvingRequestId = MutableStateFlow<String?>(null)
    val resolvingRequestId: StateFlow<String?> = _resolvingRequestId.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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
        // 空间详情、目录映射与参与设备一起刷新
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

    /** 立即同步：前台时直接调用 SyncEngine（绕过 WorkManager 的调度延迟），同时排一个后台 Work 兜底。 */
    fun syncNow() {
        if (_syncing.value) return
        if (backgroundSyncing.value) {
            // 后台同步正在执行（含扫描），再点一次只会排队并重复扫描，直接提示即可。
            viewModelScope.launch { _messages.emit("正在同步中，请稍候") }
            return
        }
        viewModelScope.launch {
            _syncing.value = true
            try {
                syncRepository.requeueFailed(spaceId)
                // 前台直接执行，避免 MIUI JobScheduler 加的 TIMING_DELAY（实测可达 7 分钟以上）
                // 仅同步当前空间：点击「立即同步」时只扫描本空间对应的目录，不扫描其他空间。
                val result = syncEngine.syncSpace(spaceId)
                val msg = when (result) {
                    SyncRunResult.SUCCESS -> "同步完成"
                    SyncRunResult.RETRY -> "另外一台设备正在同步，请稍后"
                    SyncRunResult.FAILURE -> "同步失败，请查看任务详情"
                }
                _messages.emit(msg)
            } finally {
                _syncing.value = false
            }
        }
        // 兜底：ViewModel 被销毁时 WorkManager 会接管
        scheduleSync()
    }

    /** 刷新：仅执行队列中已存在的任务（下载/上传/变更），不扫描、不拉取新变更，与「立即同步」区分。 */
    fun runQueuedTasks() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val result = syncEngine.runQueuedTasks()
                val msg = when (result) {
                    SyncRunResult.SUCCESS -> "队列任务已处理完成"
                    SyncRunResult.RETRY -> "暂时无法执行，请稍后重试"
                    SyncRunResult.FAILURE -> "部分任务执行失败，请查看任务详情"
                }
                _messages.emit(msg)
            } finally {
                _syncing.value = false
            }
        }
    }

    /** 重新排队单个任务（待处理/等待重试/失败均可重试）。 */
    fun retryTask(task: LocalSyncTask) {
        viewModelScope.launch {
            syncRepository.retryTask(task.id)
            scheduleSync()
            _messages.emit("已重新排队：${task.remoteRelativePath.substringAfterLast('/')}")
        }
    }

    /** 清空当前空间的已完成任务。 */
    fun clearCompleted() {
        viewModelScope.launch {
            val n = syncRepository.clearCompletedBySpace(spaceId)
            _messages.emit("已清空 $n 条完成记录")
        }
    }

    /** 任务卡片取消：进行中的上传/下载移到队列末尾（保留任务），其余未完成任务直接移除。 */
    fun cancelTask(task: LocalSyncTask) {
        viewModelScope.launch {
            val name = task.remoteRelativePath.substringAfterLast('/')
            val runningTransfer = task.state == SyncTaskState.RUNNING &&
                (task.type == SyncTaskType.UPLOAD || task.type == SyncTaskType.DOWNLOAD)
            if (runningTransfer) {
                // 进行中的上传/下载不丢弃，移到队列末尾等本轮其他任务完成后再继续
                syncRepository.moveRunningTaskToQueueEnd(task.id)
                _messages.emit("已移到队列末尾：$name")
            } else {
                syncRepository.cancelTask(task.id)
                _messages.emit("已取消：$name")
            }
        }
    }

    /** 把该空间所有失败任务重新排队（对象已永久丢失的任务除外）。 */
    fun retryAllFailed() {
        viewModelScope.launch {
            val failed = tasks.value.filter { it.state == SyncTaskState.FAILED }
            if (failed.isEmpty()) {
                _messages.emit("没有失败任务")
                return@launch
            }
            failed.forEach { syncRepository.retryTask(it.id) }
            scheduleSync()
            _messages.emit("已重新排队 ${failed.size} 个失败任务")
        }
    }

    fun showMessage(message: String) {
        viewModelScope.launch { _messages.emit(message) }
    }

    /** 拉取空间云端存储统计（活动文件 + 回收站占用）。 */
    fun loadStorageStats() {
        if (_storageStats.value?.loading == true) return
        viewModelScope.launch {
            _storageStats.value = StorageStatsUiState(loading = true)
            _storageStats.value = try {
                val response = apiClient.api.getStorageStats(spaceId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    StorageStatsUiState(
                        loading = false,
                        activeFileCount = body.activeFileCount,
                        activeSize = body.activeSize.toLongOrNull() ?: 0L,
                        trashCount = body.trashCount,
                        trashSize = body.trashSize.toLongOrNull() ?: 0L
                    )
                } else {
                    StorageStatsUiState(loading = false, error = "加载失败（${response.code()}）")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StorageStatsUiState(loading = false, error = e.message ?: "网络异常")
            }
        }
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

    /** 管理参与设备：勾选加入或移出本账号下的设备。 */
    fun setMembership(deviceId: String, join: Boolean) {
        if (_membershipChanging.value != null) return
        viewModelScope.launch {
            _membershipChanging.value = deviceId
            try {
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
                        if (isSelf && !join) {
                            _members.value = emptyList()
                            spaceRepository.refreshSpaces()
                        } else {
                            val refreshed = spaceRepository.loadMembers(spaceId)
                            if (refreshed is ApiResult.Success) _members.value = refreshed.data
                        }
                    }
                    is ApiResult.Failure -> _messages.emit("成员变更失败：${result.message}")
                }
            } finally {
                _membershipChanging.value = null
            }
        }
    }

    /** 从设备退出空间：移出本机设备。 */
    fun exitSpace() {
        currentDeviceId?.let { setMembership(it, false) }
    }
}

/** 云端存储统计的 UI 状态。 */
data class StorageStatsUiState(
    val loading: Boolean,
    val activeFileCount: Int = 0,
    val activeSize: Long = 0,
    val trashCount: Int = 0,
    val trashSize: Long = 0,
    val error: String? = null
)

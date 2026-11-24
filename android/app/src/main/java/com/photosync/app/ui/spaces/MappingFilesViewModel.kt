package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.ConflictDto
import com.photosync.app.data.repository.MaintenanceRepository
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.data.repository.SyncRepository
import com.photosync.app.data.sync.FileFailureInfo
import com.photosync.app.data.sync.SyncEngine
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.util.ErrorMessages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 文件条目失败处理动作。 */
enum class FileResolveAction { KEEP_LOCAL, KEEP_CLOUD, DELETE_BOTH }

class MappingFilesViewModel(
    private val spaceRepository: SpaceRepository,
    private val syncRepository: SyncRepository,
    private val syncEngine: SyncEngine,
    private val maintenanceRepository: MaintenanceRepository,
    private val app: PhotoSyncApp,
    mappingId: String
) : ViewModel() {
    val mapping: StateFlow<LocalFolderMapping?> = spaceRepository.observeMapping(mappingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val files: StateFlow<List<LocalFileIndex>> = spaceRepository.observeFiles(mappingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 失败原因：本地相对路径 → 对应失败任务的错误信息。 */
    val taskErrors: StateFlow<Map<String, String>> = mapping
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(emptyMap())
            } else {
                syncRepository.observeActiveTasks(current.spaceId).map { tasks ->
                    tasks.mapNotNull { task ->
                        task.error?.let { task.localRelativePath to it }
                    }.toMap()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _resolvingFileId = MutableStateFlow<String?>(null)
    val resolvingFileId: StateFlow<String?> = _resolvingFileId.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages

    /** 服务端上传冲突（按 fileId 索引，仅 pending），在文件条目上就地处理。 */
    private val _conflicts = MutableStateFlow<Map<String, ConflictDto>>(emptyMap())
    val conflicts: StateFlow<Map<String, ConflictDto>> = _conflicts.asStateFlow()

    private val _conflictResolvingId = MutableStateFlow<String?>(null)
    val conflictResolvingId: StateFlow<String?> = _conflictResolvingId.asStateFlow()

    init {
        refreshConflicts()
    }

    /** 拉取本空间的服务端冲突；失败静默（不打扰文件列表）。 */
    fun refreshConflicts() {
        viewModelScope.launch {
            val currentSpaceId = mapping.value?.spaceId ?: return@launch
            when (val result = maintenanceRepository.conflicts(currentSpaceId)) {
                is ApiResult.Success -> _conflicts.value = result.data
                    .filter { it.status == "pending" }
                    .associateBy { it.fileId }
                is ApiResult.Failure -> Unit
            }
        }
    }

    /** 冲突解决：server=保留服务器版本 / client=采纳本机提交 / both=两者都保留。 */
    fun resolveConflict(conflict: ConflictDto, resolution: String) {
        if (_conflictResolvingId.value != null) return
        viewModelScope.launch {
            _conflictResolvingId.value = conflict.conflictId
            val result = maintenanceRepository.resolveConflict(conflict.conflictId, resolution)
            _conflictResolvingId.value = null
            when (result) {
                is ApiResult.Success -> {
                    _messages.emit("冲突已解决：${conflict.fileName}")
                    _conflicts.value = _conflicts.value - conflict.fileId
                    SyncScheduler.enqueue(app, force = true)
                }
                is ApiResult.Failure -> _messages.emit(ErrorMessages.of(result))
            }
        }
    }

    fun isResolvable(file: LocalFileIndex): Boolean = syncEngine.isResolvableFileEntry(file)

    /** 失败详情弹层状态：点击「同步失败」标签后加载云端/本地文件信息。 */
    private val _failureDetail = MutableStateFlow<FailureDetailUi?>(null)
    val failureDetail: StateFlow<FailureDetailUi?> = _failureDetail.asStateFlow()

    fun openFailureDetail(file: LocalFileIndex) {
        if (_failureDetail.value?.loading == true) return
        viewModelScope.launch {
            _failureDetail.value = FailureDetailUi(file = file, loading = true)
            _failureDetail.value = FailureDetailUi(
                file = file,
                loading = false,
                info = syncEngine.fileFailureInfo(file)
            )
        }
    }

    fun closeFailureDetail() {
        _failureDetail.value = null
    }

    /** 失败处理：保留本地 / 保留云端 / 两端都删除。 */
    fun resolveFile(file: LocalFileIndex, action: FileResolveAction) {
        if (_resolvingFileId.value != null) return
        viewModelScope.launch {
            _resolvingFileId.value = file.id.toString()
            val error = when (action) {
                FileResolveAction.KEEP_LOCAL -> syncEngine.resolveFileKeepLocal(file)
                FileResolveAction.KEEP_CLOUD -> syncEngine.resolveFileKeepCloud(file)
                FileResolveAction.DELETE_BOTH -> syncEngine.resolveFileDeleteBoth(file)
            }
            _resolvingFileId.value = null
            _messages.emit(
                if (error == null) {
                    when (action) {
                        FileResolveAction.KEEP_LOCAL -> "已保留本地版本，正在覆盖云端"
                        FileResolveAction.KEEP_CLOUD -> "已保留云端版本，将重新下载"
                        FileResolveAction.DELETE_BOTH -> "已删除本地与云端文件"
                    }
                } else {
                    "处理失败：$error"
                }
            )
            if (error == null) {
                _failureDetail.value = null
                SyncScheduler.enqueue(app, force = true)
            }
        }
    }
}

package com.photosync.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.TrashItemDto
import com.photosync.app.data.repository.MaintenanceRepository
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.ui.util.ErrorMessages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrashUiState(
    val items: List<TrashItemDto> = emptyList(),
    val loading: Boolean = true,
    val actionTrashId: String? = null,
    val clearing: Boolean = false,
    val error: String? = null
)

/** 空间回收站：列表、恢复与彻底清理（服务端 Tombstone）。 */
class TrashViewModel(
    private val spaceRepository: SpaceRepository,
    private val repository: MaintenanceRepository,
    private val spaceId: String
) : ViewModel() {

    /** 只有空间管理员（owner/admin）可以清空回收站。 */
    val canClearTrash: StateFlow<Boolean> = spaceRepository.observeSpace(spaceId)
        .map { it?.role == "admin" || it?.role == "owner" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = repository.trash(spaceId)) {
                is ApiResult.Success -> _state.value = TrashUiState(items = result.data, loading = false)
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    error = ErrorMessages.of(result)
                )
            }
        }
    }

    fun restore(item: TrashItemDto) {
        if (_state.value.actionTrashId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(actionTrashId = item.trashId)
            val result = repository.restore(item.trashId)
            _state.value = _state.value.copy(actionTrashId = null)
            when (result) {
                is ApiResult.Success -> {
                    _messages.emit("已恢复：${item.fileName}")
                    refresh()
                }
                is ApiResult.Failure -> _messages.emit(ErrorMessages.of(result))
            }
        }
    }

    /** 清空回收站：服务端没有批量接口，逐条彻底删除并汇总结果。 */
    fun purgeAll() {
        if (_state.value.clearing || _state.value.actionTrashId != null) return
        val items = _state.value.items
        if (items.isEmpty()) {
            _messages.tryEmit("回收站已为空")
            return
        }
        viewModelScope.launch {
            val role = spaceRepository.observeSpace(spaceId).first()?.role
            if (role != "admin" && role != "owner") {
                _messages.emit("只有空间管理员可以清空回收站")
                return@launch
            }
            _state.value = _state.value.copy(clearing = true)
            var purged = 0
            var failed = 0
            items.forEach { item ->
                when (repository.purge(item.trashId)) {
                    is ApiResult.Success -> purged++
                    is ApiResult.Failure -> failed++
                }
            }
            _state.value = _state.value.copy(clearing = false)
            refresh()
            _messages.emit(
                if (failed == 0) {
                    "已清空回收站：$purged 个文件"
                } else {
                    "已删除 $purged 个文件，$failed 个删除失败"
                }
            )
        }
    }

    fun purge(item: TrashItemDto) {
        if (_state.value.actionTrashId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(actionTrashId = item.trashId)
            val result = repository.purge(item.trashId)
            _state.value = _state.value.copy(actionTrashId = null)
            when (result) {
                is ApiResult.Success -> {
                    _messages.emit("已彻底删除：${item.fileName}")
                    refresh()
                }
                is ApiResult.Failure -> _messages.emit(ErrorMessages.of(result))
            }
        }
    }
}

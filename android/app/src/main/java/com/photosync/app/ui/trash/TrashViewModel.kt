package com.photosync.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.TrashItemDto
import com.photosync.app.data.repository.MaintenanceRepository
import com.photosync.app.ui.util.ErrorMessages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrashUiState(
    val items: List<TrashItemDto> = emptyList(),
    val loading: Boolean = true,
    val actionTrashId: String? = null,
    val error: String? = null
)

/** 空间回收站：列表、恢复与彻底清理（服务端 Tombstone）。 */
class TrashViewModel(
    private val repository: MaintenanceRepository,
    private val spaceId: String
) : ViewModel() {

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

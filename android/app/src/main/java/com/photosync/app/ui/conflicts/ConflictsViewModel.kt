package com.photosync.app.ui.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.ConflictDto
import com.photosync.app.data.repository.MaintenanceRepository
import com.photosync.app.ui.util.ErrorMessages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConflictsUiState(
    val conflicts: List<ConflictDto> = emptyList(),
    val loading: Boolean = true,
    val resolvingId: String? = null,
    val error: String? = null
)

/** 冲突列表与三种解决方式（server / client / both）。 */
class ConflictsViewModel(
    private val repository: MaintenanceRepository,
    private val spaceId: String,
    private val onResolved: () -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow(ConflictsUiState())
    val state: StateFlow<ConflictsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = repository.conflicts(spaceId)) {
                is ApiResult.Success -> _state.value = ConflictsUiState(
                    conflicts = result.data,
                    loading = false
                )
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    error = ErrorMessages.of(result)
                )
            }
        }
    }

    fun resolve(conflict: ConflictDto, resolution: String) {
        if (_state.value.resolvingId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(resolvingId = conflict.conflictId)
            val result = repository.resolveConflict(conflict.conflictId, resolution)
            _state.value = _state.value.copy(resolvingId = null)
            when (result) {
                is ApiResult.Success -> {
                    _messages.emit("冲突已解决：${conflict.fileName}")
                    refresh()
                    onResolved()
                }
                is ApiResult.Failure -> _messages.emit(ErrorMessages.of(result))
            }
        }
    }
}

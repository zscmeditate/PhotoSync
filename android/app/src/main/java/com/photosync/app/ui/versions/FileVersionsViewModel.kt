package com.photosync.app.ui.versions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.remote.RemoteFileVersionDto
import com.photosync.app.data.repository.MaintenanceRepository
import com.photosync.app.ui.util.ErrorMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VersionsUiState(
    val versions: List<RemoteFileVersionDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

/** 文件历史版本列表（只读）。 */
class FileVersionsViewModel(
    private val repository: MaintenanceRepository,
    private val fileId: String
) : ViewModel() {

    private val _state = MutableStateFlow(VersionsUiState())
    val state: StateFlow<VersionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = repository.versions(fileId)) {
                is ApiResult.Success -> _state.value = VersionsUiState(
                    versions = result.data.sortedByDescending { it.version },
                    loading = false
                )
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    error = ErrorMessages.of(result)
                )
            }
        }
    }
}

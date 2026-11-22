package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.SpaceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SpaceListViewModel(
    private val spaceRepository: SpaceRepository
) : ViewModel() {
    val spaces: StateFlow<List<LocalSpace>> = spaceRepository.spaces
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mappings: StateFlow<List<LocalFolderMapping>> = spaceRepository.observeMappingsForCurrentDevice()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
            _refreshing.value = false
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

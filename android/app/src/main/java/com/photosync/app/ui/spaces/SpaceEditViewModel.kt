package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.SpaceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpaceEditUiState(
    val name: String = "",
    val description: String = "",
    val deviceId: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null
)

data class SpaceSavedEvent(val spaceId: String, val created: Boolean)

class SpaceEditViewModel(
    private val spaceRepository: SpaceRepository,
    private val spaceId: String?
) : ViewModel() {
    private val _state = MutableStateFlow(
        SpaceEditUiState(
            deviceId = spaceRepository.currentDeviceId().orEmpty(),
            loading = spaceId != null
        )
    )
    val state: StateFlow<SpaceEditUiState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<SpaceSavedEvent>()
    val saved: SharedFlow<SpaceSavedEvent> = _saved.asSharedFlow()

    init {
        if (spaceId != null) load(spaceId)
    }

    private fun load(spaceId: String) {
        viewModelScope.launch {
            var space = spaceRepository.getSpace(spaceId)
            if (space == null) {
                spaceRepository.refreshSpace(spaceId)
                space = spaceRepository.getSpace(spaceId)
            }
            _state.value = if (space == null) {
                _state.value.copy(loading = false, error = "未找到该同步空间")
            } else {
                _state.value.copy(
                    name = space.name,
                    description = space.description.orEmpty(),
                    loading = false
                )
            }
        }
    }

    fun updateName(value: String) {
        _state.value = _state.value.copy(name = value, error = null)
    }

    fun updateDescription(value: String) {
        _state.value = _state.value.copy(description = value, error = null)
    }

    fun save() {
        val current = _state.value
        val name = current.name.trim()
        if (name.isBlank()) {
            _state.value = current.copy(error = "请输入空间名称")
            return
        }
        if (spaceId == null && current.deviceId.isBlank()) {
            _state.value = current.copy(error = "本机设备尚未注册，请先在设备管理页注册")
            return
        }
        if (current.saving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            val description = current.description.trim()
            val result: ApiResult<String> = if (spaceId == null) {
                when (val created = spaceRepository.createSpace(name, description.ifEmpty { null })) {
                    is ApiResult.Success -> ApiResult.Success(created.data.id)
                    is ApiResult.Failure -> created
                }
            } else {
                when (val updated = spaceRepository.updateSpace(spaceId, name, description)) {
                    is ApiResult.Success -> ApiResult.Success(spaceId)
                    is ApiResult.Failure -> updated
                }
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(saving = false)
                    _saved.emit(SpaceSavedEvent(result.data, spaceId == null))
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(saving = false, error = result.message)
                }
            }
        }
    }
}

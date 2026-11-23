package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalSpace
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.SpaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 列出当前账号下已经存在、但本机尚未绑定目录的空间，供「加入空间」选择。 */
class JoinSpaceViewModel(
    private val spaceRepository: SpaceRepository
) : ViewModel() {
    private val _spaces = MutableStateFlow<List<LocalSpace>>(emptyList())
    val spaces: StateFlow<List<LocalSpace>> = _spaces.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            when (val result = spaceRepository.refreshSpaces()) {
                is ApiResult.Failure -> _loadError.value = result.message
                is ApiResult.Success -> loadCandidates()
            }
            _refreshing.value = false
        }
    }

    private suspend fun loadCandidates() {
        val deviceId = spaceRepository.currentDeviceId()
        if (deviceId == null) {
            _loadError.value = "本机设备尚未注册，请先在设备管理页注册"
            return
        }
        val candidates = mutableListOf<LocalSpace>()
        for (space in spaceRepository.getAllSpaces()) {
            when (val result = spaceRepository.hasDeviceMapping(space.id, deviceId)) {
                is ApiResult.Success -> if (!result.data) candidates += space
                is ApiResult.Failure -> {
                    _loadError.value = result.message
                    return
                }
            }
        }
        _spaces.value = candidates
        _loadError.value = null
    }
}

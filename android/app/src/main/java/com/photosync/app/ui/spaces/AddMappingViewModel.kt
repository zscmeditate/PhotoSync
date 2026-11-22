package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.ScanPreview
import com.photosync.app.data.repository.SpaceRepository
import com.photosync.app.data.sync.RemotePrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddMappingUiState(
    val treeUri: String? = null,
    val localPathLabel: String = "",
    val remotePrefix: String = "",
    val includeSubdirs: Boolean = true,
    val onlyMedia: Boolean = false,
    val creating: Boolean = false,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val currentFileName: String = "",
    val createdMappingId: String? = null,
    val preview: ScanPreview? = null,
    val error: String? = null,
    val prefixServerError: String? = null
)

class AddMappingViewModel(
    private val spaceRepository: SpaceRepository,
    private val spaceId: String
) : ViewModel() {
    private val _state = MutableStateFlow(AddMappingUiState())
    val state: StateFlow<AddMappingUiState> = _state.asStateFlow()

    val existingMappings: StateFlow<List<LocalFolderMapping>> = spaceRepository.observeMappings(spaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var scanJob: Job? = null

    fun selectDirectory(treeUri: String, label: String) {
        _state.value = _state.value.copy(
            treeUri = treeUri,
            localPathLabel = label,
            remotePrefix = _state.value.remotePrefix.ifBlank { label },
            error = null,
            prefixServerError = null
        )
    }

    fun updateRemotePrefix(value: String) {
        _state.value = _state.value.copy(
            remotePrefix = value,
            error = null,
            prefixServerError = null
        )
    }

    fun updateIncludeSubdirs(value: Boolean) {
        _state.value = _state.value.copy(includeSubdirs = value)
    }

    fun updateOnlyMedia(value: Boolean) {
        _state.value = _state.value.copy(onlyMedia = value)
    }

    fun showError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    fun confirm() {
        val current = _state.value
        if (current.creating || current.scanning) return
        val treeUri = current.treeUri
        if (treeUri == null) {
            showError("请先通过系统文件选择器选择本地目录")
            return
        }
        val validation = RemotePrefix.validate(current.remotePrefix)
        if (validation.error != null) {
            _state.value = current.copy(prefixServerError = validation.error)
            return
        }
        if (existingMappings.value.any { RemotePrefix.overlaps(it.remotePrefix, validation.normalized) }) {
            _state.value = current.copy(prefixServerError = "该路径与同空间的本机已有映射重叠")
            return
        }

        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null, prefixServerError = null)
            when (
                val result = spaceRepository.createMapping(
                    spaceId = spaceId,
                    localPathLabel = current.localPathLabel,
                    treeUri = treeUri,
                    remotePrefix = validation.normalized,
                    includeSubdirs = current.includeSubdirs,
                    onlyMedia = current.onlyMedia
                )
            ) {
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(
                        creating = false,
                        error = if (result.code == "MAPPING_OVERLAP") null else result.message,
                        prefixServerError = if (result.code == "MAPPING_OVERLAP") {
                            "服务端检测到路径重叠，请修改同步空间内路径"
                        } else {
                            null
                        }
                    )
                }
                is ApiResult.Success -> {
                    val mapping = result.data
                    _state.value = _state.value.copy(
                        creating = false,
                        scanning = true,
                        createdMappingId = mapping.id,
                        scannedCount = 0,
                        currentFileName = ""
                    )
                    try {
                        when (val scan = spaceRepository.scanMapping(mapping.id) { progress ->
                            _state.value = _state.value.copy(
                                scannedCount = progress.scannedCount,
                                currentFileName = progress.currentFileName
                            )
                        }) {
                            is ApiResult.Success -> {
                                _state.value = _state.value.copy(scanning = false, preview = scan.data)
                            }
                            is ApiResult.Failure -> {
                                val localScanCompleted = scan.code !in setOf("ACCESS_REVOKED", "SCAN_ERROR")
                                _state.value = _state.value.copy(
                                    scanning = false,
                                    error = if (localScanCompleted) {
                                        "目录已添加，本地扫描完成，但差异预览失败：${scan.message}"
                                    } else {
                                        "目录已添加，但扫描失败：${scan.message}。旧索引（如有）已保留。"
                                    }
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        _state.value = _state.value.copy(
                            scanning = false,
                            error = "已取消扫描；目录映射已保存，可稍后重新扫描"
                        )
                        throw e
                    }
                }
            }
        }
        scanJob?.invokeOnCompletion { scanJob = null }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }
}

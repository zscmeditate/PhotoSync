package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.BindOutcome
import com.photosync.app.data.repository.SpaceRepository
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
    val onlyMedia: Boolean = false,
    val creating: Boolean = false,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val currentFileName: String = "",
    val createdMappingId: String? = null,
    /** 绑定与首次扫描完成，界面应立即返回空间页。 */
    val bindCompleted: Boolean = false,
    /** 本机还不是成员：目录已选择并提交加入申请，等待主设备同意。 */
    val joinSubmitted: Boolean = false,
    val error: String? = null
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
            error = null
        )
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

        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null)
            when (
                val result = spaceRepository.bindDirectory(
                    spaceId = spaceId,
                    localPathLabel = current.localPathLabel,
                    treeUri = treeUri,
                    onlyMedia = current.onlyMedia
                )
            ) {
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(creating = false, error = result.message)
                }
                is ApiResult.Success -> when (val outcome = result.data) {
                    is BindOutcome.JoinPending -> {
                        _state.value = _state.value.copy(creating = false, joinSubmitted = true)
                    }
                    is BindOutcome.Bound -> {
                        val mapping = outcome.mapping
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
                                    // 不再展示首次同步预览，扫描完成直接返回空间页
                                    _state.value = _state.value.copy(
                                        scanning = false,
                                        bindCompleted = true
                                    )
                                }
                                is ApiResult.Failure -> {
                                    val localScanCompleted =
                                        scan.code !in setOf("ACCESS_REVOKED", "SCAN_ERROR")
                                    _state.value = _state.value.copy(
                                        scanning = false,
                                        error = if (localScanCompleted) {
                                            "目录已添加，本地扫描完成，但差异计算失败：${scan.message}"
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
        }
        scanJob?.invokeOnCompletion { scanJob = null }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }
}

package com.photosync.app.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.repository.SpaceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MappingFilesViewModel(
    spaceRepository: SpaceRepository,
    mappingId: String
) : ViewModel() {
    val mapping: StateFlow<LocalFolderMapping?> = spaceRepository.observeMapping(mappingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val files: StateFlow<List<LocalFileIndex>> = spaceRepository.observeFiles(mappingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

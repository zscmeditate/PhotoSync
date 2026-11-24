package com.photosync.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.repository.SyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 活动记录 ViewModel：观察最近任务时间线（含已完成任务）；spaceId 非空时仅该空间的记录。 */
class SyncTasksViewModel(repository: SyncRepository, spaceId: String? = null) : ViewModel() {

    val recent: StateFlow<List<LocalSyncTask>> =
        if (spaceId == null) {
            repository.observeRecentTasks(200)
        } else {
            repository.observeRecentTasks(spaceId, 200)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

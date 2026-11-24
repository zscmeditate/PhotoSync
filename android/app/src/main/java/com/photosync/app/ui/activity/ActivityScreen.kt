package com.photosync.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.spaces.SpacePageHeader
import com.photosync.app.ui.tasks.SyncTasksViewModel
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue

/** 活动记录：本机最近同步任务的时间线（含已完成、失败与冲突阻塞）。 */
@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: SyncTasksViewModel = viewModel {
        SyncTasksViewModel(app.container.syncRepository)
    }
    val tasks by viewModel.recent.collectAsStateWithLifecycle()
    val grouped = remember(tasks) {
        tasks.groupBy { TaskPresentation.formatDate(it.updatedAt) }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SpacePageHeader(title = "活动记录", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (grouped.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Filled.History,
                        title = "暂无活动",
                        subtitle = "完成一次同步后，这里会按时间记录上传、下载、删除与重命名"
                    )
                }
            } else {
                grouped.forEach { (date, items) ->
                    item(key = "header-$date") {
                        Text(
                            text = date,
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurfaceMuted,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(items, key = { it.id }) { task ->
                        ActivityRow(task)
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun ActivityRow(task: LocalSyncTask) {
    val color = TaskPresentation.stateColor(task.state)
    val error = TaskPresentation.errorText(task.error)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = TaskPresentation.typeIcon(task.type),
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${TaskPresentation.typeLabel(task.type)} · " +
                        TaskPresentation.displayName(task.remoteRelativePath),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = task.remoteRelativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = com.photosync.app.ui.theme.ErrorRed,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = TaskPresentation.stateLabel(task.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = TaskPresentation.formatTime(task.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }
        }
    }
}

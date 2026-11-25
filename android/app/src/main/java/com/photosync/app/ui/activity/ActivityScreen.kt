package com.photosync.app.ui.activity

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.ui.components.AppSnackbarHost
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.spaces.SpacePageHeader
import com.photosync.app.ui.tasks.SyncTasksViewModel
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
/** 同步日志：最近同步任务的时间线（含已完成、失败与冲突阻塞）；spaceId 非空时仅展示该空间。 */
@Composable
fun ActivityScreen(spaceId: String? = null, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: SyncTasksViewModel = viewModel {
        SyncTasksViewModel(app.container.syncRepository, app.container.spaceRepository, spaceId)
    }
    val tasks by viewModel.recent.collectAsStateWithLifecycle()
    val spaceNames by viewModel.spaceNames.collectAsStateWithLifecycle()
    val showSpaceTag = spaceId == null && spaceNames.isNotEmpty()
    val grouped = remember(tasks) {
        tasks.groupBy { TaskPresentation.formatDate(it.updatedAt) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = if (spaceId != null) "同步日志" else "全部空间日志",
                onBack = onBack,
                actions = {
                    if (tasks.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearConfirm = true },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("清空", color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (grouped.isEmpty()) {
                    item {
                        EmptyStateView(
                            icon = Icons.Filled.History,
                            title = "暂无同步日志",
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
                            ActivityRow(
                                task = task,
                                spaceName = if (showSpaceTag) spaceNames[task.spaceId] else null
                            )
                        }
                    }
                }
                item { Spacer(Modifier.size(16.dp)) }
            }
        }
        AppSnackbarHost(hostState = snackbarHostState)
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空同步日志") },
            text = {
                val scope = if (spaceId != null) "该空间的" else "所有"
                Text("确定要清空${scope}同步日志吗？共 ${tasks.size} 条记录将被删除，此操作不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearLog()
                }) {
                    Text("清空", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ActivityRow(task: LocalSyncTask, spaceName: String? = null) {
    val color = TaskPresentation.stateColor(task.state)
    val error = TaskPresentation.errorText(task.error)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：图标 + 类型 + 标题（文件名，超长省略）+ 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TaskPresentation.typeIcon(task.type),
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TaskPresentation.typeLabel(task.type),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TaskPresentation.displayName(task.remoteRelativePath),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TaskPresentation.stateLabel(task.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 第二行：完整文件名（含路径，不省略，长名自动换行）
            Text(
                text = task.remoteRelativePath,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            // 第三行：时间靠左，空间标签贴到卡片右下角
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = TaskPresentation.formatTime(task.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
                Spacer(Modifier.weight(1f))
                if (!spaceName.isNullOrBlank()) {
                    SpaceTag(text = spaceName)
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 小而轻的空间标签：浅蓝底色 + 深蓝文字。 */
@Composable
private fun SpaceTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = PrimaryBlue,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PrimaryBlue.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

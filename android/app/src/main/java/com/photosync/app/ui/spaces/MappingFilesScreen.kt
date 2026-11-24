package com.photosync.app.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalFileIndex
import com.photosync.app.data.remote.ConflictDto
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.WarningOrange
import com.photosync.app.util.formatIsoTime

@Composable
fun MappingFilesScreen(
    mappingId: String,
    onBack: () -> Unit,
    onOpenVersions: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: MappingFilesViewModel = viewModel(key = mappingId) {
        MappingFilesViewModel(
            spaceRepository = app.container.spaceRepository,
            syncRepository = app.container.syncRepository,
            syncEngine = app.container.syncEngine,
            maintenanceRepository = app.container.maintenanceRepository,
            app = app,
            mappingId = mappingId
        )
    }
    val mapping by viewModel.mapping.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val taskErrors by viewModel.taskErrors.collectAsStateWithLifecycle()
    val resolvingFileId by viewModel.resolvingFileId.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val conflictResolvingId by viewModel.conflictResolvingId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 失败处理选择：file to action，确认后才执行
    var resolveChoice by remember { mutableStateOf<Pair<LocalFileIndex, FileResolveAction>?>(null) }
    // 服务端冲突处理选择：conflict to resolution
    var conflictPending by remember { mutableStateOf<Pair<ConflictDto, String>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(mapping?.id) {
        viewModel.refreshConflicts()
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SpacePageHeader(title = mapping?.localPathLabel ?: "映射文件", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "空间根目录",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            mapping?.let { MappingStatusChip(it.status) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "扫描时间：${formatIsoTime(mapping?.lastScannedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                        Text(
                            text = "索引状态：${if (mapping?.lastScanComplete == true) "完整" else "未完成"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mapping?.lastScanComplete == true) PrimaryBlue else OnSurfaceMuted
                        )
                    }
                }
            }
            if (files.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Filled.Description,
                        title = "暂无本地文件索引",
                        subtitle = "返回空间详情点击「立即同步」后会在此显示索引；扫描失败不会清空已有索引"
                    )
                }
            } else {
                items(files, key = { it.id }) { file ->
                    val resolvable = viewModel.isResolvable(file)
                    val conflict = file.remoteFileId?.let { conflicts[it] }
                    FileIndexRow(
                        file = file,
                        errorText = if (resolvable) {
                            taskErrors[file.relativePath]?.let { TaskPresentation.errorText(it) } ?: "同步失败"
                        } else {
                            null
                        },
                        resolvable = resolvable,
                        resolving = resolvingFileId == file.id.toString(),
                        conflict = conflict,
                        conflictBusy = conflict != null && conflictResolvingId == conflict.conflictId,
                        onResolveConflict = { resolution ->
                            conflict?.let { conflictPending = it to resolution }
                        },
                        onClick = file.remoteFileId?.let { { onOpenVersions(it) } },
                        onKeepLocal = { resolveChoice = file to FileResolveAction.KEEP_LOCAL },
                        onKeepCloud = { resolveChoice = file to FileResolveAction.KEEP_CLOUD },
                        onDeleteBoth = { resolveChoice = file to FileResolveAction.DELETE_BOTH }
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState)
    }

    resolveChoice?.let { (file, action) ->
        ConfirmBottomSheet(
            title = when (action) {
                FileResolveAction.KEEP_LOCAL -> "保留本地版本"
                FileResolveAction.KEEP_CLOUD -> "保留云端版本"
                FileResolveAction.DELETE_BOTH -> "删除两端文件"
            },
            message = buildString {
                append(file.fileName)
                append('\n')
                append(file.relativePath)
                append("\n\n")
                when (action) {
                    FileResolveAction.KEEP_LOCAL ->
                        append("确认后将以本地内容覆盖云端同名文件，保持两端严格一致。")
                    FileResolveAction.KEEP_CLOUD ->
                        append("确认后本地当前文件将移入应用回收站，随后重新下载云端版本，保持两端严格一致。")
                    FileResolveAction.DELETE_BOTH ->
                        append("确认后本地文件将移入应用回收站，云端文件一并删除，其他设备会同步移除该文件。")
                }
            },
            confirmText = when (action) {
                FileResolveAction.DELETE_BOTH -> "确认删除"
                else -> "确认保留"
            },
            onConfirm = {
                viewModel.resolveFile(file, action)
                resolveChoice = null
            },
            onDismiss = { resolveChoice = null }
        )
    }

    conflictPending?.let { (conflict, resolution) ->
        ConfirmBottomSheet(
            title = when (resolution) {
                "server" -> "保留服务器版本"
                "client" -> "采纳本机提交"
                else -> "两者都保留"
            },
            message = when (resolution) {
                "server" -> "「${conflict.fileName}」将保留服务器上的版本 ${conflict.serverVersion}，本机提交的内容会被丢弃。"
                "client" -> "「${conflict.fileName}」将使用本机提交的内容覆盖服务器版本 ${conflict.serverVersion}。"
                else -> "「${conflict.fileName}」会保留服务器版本，并将本机提交保存为冲突副本，两种内容都不会丢失。"
            },
            confirmText = "确认",
            onConfirm = {
                viewModel.resolveConflict(conflict, resolution)
                conflictPending = null
            },
            onDismiss = { conflictPending = null }
        )
    }
}

@Composable
private fun FileIndexRow(
    file: LocalFileIndex,
    errorText: String?,
    resolvable: Boolean,
    resolving: Boolean,
    conflict: ConflictDto?,
    conflictBusy: Boolean,
    onResolveConflict: (String) -> Unit,
    onClick: (() -> Unit)?,
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onDeleteBoth: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = PrimaryBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FileStatusChip(file.status)
            }
            Text(
                text = file.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 5.dp)
            )
            Text(
                text = "${formatFileSize(file.size)} · ${formatModifiedAt(file.modifiedAt)} · ${if (file.sha256 == null) "未校验" else "SHA-256 已校验"}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp)
            )
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (conflict != null) {
                Text(
                    text = "云端冲突待处理 · 服务器版本 ${conflict.serverVersion} · 本机提交 ${formatFileSize(conflict.submitted.size.toLongOrNull() ?: 0L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningOrange,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onResolveConflict("server") }, enabled = !conflictBusy) {
                        Text("服务器版本", color = PrimaryBlue)
                    }
                    TextButton(onClick = { onResolveConflict("client") }, enabled = !conflictBusy) {
                        Text("本机提交", color = PrimaryBlue)
                    }
                    TextButton(onClick = { onResolveConflict("both") }, enabled = !conflictBusy) {
                        Text("两者都保留", color = PrimaryBlue)
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            if (resolvable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onKeepLocal, enabled = !resolving) {
                        Text("本地", color = PrimaryBlue)
                    }
                    TextButton(onClick = onKeepCloud, enabled = !resolving) {
                        Text("云端", color = PrimaryBlue)
                    }
                    TextButton(onClick = onDeleteBoth, enabled = !resolving) {
                        Text("删除", color = ErrorRed)
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

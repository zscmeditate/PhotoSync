package com.photosync.app.ui.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.FileSyncStatus
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
    val failureDetail by viewModel.failureDetail.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 列表排序：字段 + 正序/倒序
    var sortKey by rememberSaveable { mutableStateOf(FileSortKey.NAME) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    val sortedFiles = remember(files, sortKey, sortAscending) {
        sortIndexFiles(files, sortKey, sortAscending)
    }
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
                            text = "索引状态：${if (mapping?.lastScanComplete == true) "完整" else "未完成"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mapping?.lastScanComplete == true) PrimaryBlue else OnSurfaceMuted
                        )
                        Text(
                            text = "扫描时间：${formatIsoTime(mapping?.lastScannedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                        // 文件统计：总数与已同步数量常显，待同步/冲突/失败仅在存在时补充
                        val syncedCount = files.count { it.status == com.photosync.app.data.local.FileSyncStatus.SYNCED }
                        val pendingCount = files.count {
                            it.status == com.photosync.app.data.local.FileSyncStatus.PENDING_UPLOAD ||
                                it.status == com.photosync.app.data.local.FileSyncStatus.PENDING_DOWNLOAD ||
                                it.status == com.photosync.app.data.local.FileSyncStatus.LOCAL_ONLY
                        }
                        val conflictCount = files.count { it.status == com.photosync.app.data.local.FileSyncStatus.CONFLICT }
                        val failedCount = files.count { it.status == com.photosync.app.data.local.FileSyncStatus.SYNC_ERROR }
                        Text(
                            text = buildString {
                                append("文件数量：${files.size} · 已同步 $syncedCount")
                                if (pendingCount > 0) append(" · 待同步 $pendingCount")
                                if (conflictCount > 0) append(" · 冲突 $conflictCount")
                                if (failedCount > 0) append(" · 失败 $failedCount")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }
            if (files.isNotEmpty()) {
                item {
                    FileSortBar(
                        sortKey = sortKey,
                        ascending = sortAscending,
                        onKeyChange = { key ->
                            sortKey = key
                            // 时间/大小/状态默认倒序（新→旧、大→小），文件名默认正序
                            sortAscending = key == FileSortKey.NAME
                        },
                        onToggleOrder = { sortAscending = !sortAscending }
                    )
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
                itemsIndexed(sortedFiles, key = { _, file -> file.id }) { index, file ->
                    val failed = viewModel.isResolvable(file)
                    val conflict = file.remoteFileId?.let { conflicts[it] }
                    FileIndexRow(
                        order = index + 1,
                        file = file,
                        errorText = if (failed) {
                            taskErrors[file.relativePath]?.let { TaskPresentation.errorText(it) } ?: "同步失败"
                        } else {
                            null
                        },
                        failed = failed,
                        conflict = conflict,
                        conflictBusy = conflict != null && conflictResolvingId == conflict.conflictId,
                        onResolveConflict = { resolution ->
                            conflict?.let { conflictPending = it to resolution }
                        },
                        onOpenFailure = { viewModel.openFailureDetail(file) },
                        onClick = file.remoteFileId?.let { { onOpenVersions(it) } }
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState)
    }

    failureDetail?.let { detail ->
        FailureDetailSheet(
            detail = detail,
            busy = resolvingFileId == detail.file.id.toString(),
            onKeepLocal = { viewModel.resolveFile(detail.file, FileResolveAction.KEEP_LOCAL) },
            onKeepCloud = { viewModel.resolveFile(detail.file, FileResolveAction.KEEP_CLOUD) },
            onDeleteBoth = { viewModel.resolveFile(detail.file, FileResolveAction.DELETE_BOTH) },
            onDismiss = { viewModel.closeFailureDetail() }
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

/** 本地目录文件列表排序字段。 */
private enum class FileSortKey(val label: String) {
    NAME("文件名"),
    MODIFIED("修改时间"),
    SIZE("大小"),
    STATUS("同步状态")
}

/** 状态排序优先级：冲突/失败等需要处理的状态排在前面。 */
private fun statusRank(status: String): Int = when (status) {
    FileSyncStatus.CONFLICT -> 0
    FileSyncStatus.SYNC_ERROR -> 1
    FileSyncStatus.PENDING_UPLOAD -> 2
    FileSyncStatus.PENDING_DOWNLOAD -> 3
    FileSyncStatus.LOCAL_ONLY -> 4
    FileSyncStatus.SYNCED -> 5
    else -> 6
}

private fun sortIndexFiles(
    files: List<LocalFileIndex>,
    key: FileSortKey,
    ascending: Boolean
): List<LocalFileIndex> {
    val comparator: Comparator<LocalFileIndex> = when (key) {
        FileSortKey.NAME -> compareBy<LocalFileIndex> { it.fileName.lowercase() }
            .thenBy { it.relativePath.lowercase() }
        FileSortKey.MODIFIED -> compareBy { it.modifiedAt }
        FileSortKey.SIZE -> compareBy { it.size }
        FileSortKey.STATUS -> compareBy<LocalFileIndex> { statusRank(it.status) }
            .thenBy { it.fileName.lowercase() }
    }
    return files.sortedWith(if (ascending) comparator else comparator.reversed())
}

/** 列表排序条：选择字段 + 正序/倒序切换。 */
@Composable
private fun FileSortBar(
    sortKey: FileSortKey,
    ascending: Boolean,
    onKeyChange: (FileSortKey) -> Unit,
    onToggleOrder: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Box {
            TextButton(onClick = { expanded = true }) {
                Text("排序：${sortKey.label}", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                FileSortKey.entries.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(key.label) },
                        onClick = {
                            onKeyChange(key)
                            expanded = false
                        }
                    )
                }
            }
        }
        IconButton(onClick = onToggleOrder) {
            Icon(
                imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (ascending) "正序，点击切换为倒序" else "倒序，点击切换为正序"
            )
        }
    }
}

@Composable
private fun FileIndexRow(
    order: Int,
    file: LocalFileIndex,
    errorText: String?,
    failed: Boolean,
    conflict: ConflictDto?,
    conflictBusy: Boolean,
    onResolveConflict: (String) -> Unit,
    onOpenFailure: () -> Unit,
    onClick: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box {
            Column(
                modifier = Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)
            ) {
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
                    if (failed) {
                        // 点击「同步失败」标签打开失败详情与处理弹层
                        Box(modifier = Modifier.clickable(onClick = onOpenFailure)) {
                            FileStatusChip(file.status)
                        }
                    } else {
                        FileStatusChip(file.status)
                    }
                }
                Text(
                    text = file.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(top = 5.dp)
                )
                // 元信息行：右侧紧跟列表序号
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatFileSize(file.size)} · ${formatModifiedAt(file.modifiedAt)} · ${if (file.sha256 == null) "未校验" else "SHA-256 已校验"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = order.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .background(PrimaryBlue, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
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
            }
        }
    }
}


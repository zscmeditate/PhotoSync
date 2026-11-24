package com.photosync.app.ui.spaces

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalDevice
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.remote.JoinRequestDto
import com.photosync.app.data.remote.SpaceMemberDto
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SectionHeader
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.util.formatIsoTime

@Composable
fun SpaceDetailScreen(
    spaceId: String,
    onBack: () -> Unit,
    onEditSpace: (String) -> Unit,
    onAddMapping: (String) -> Unit,
    onOpenMapping: (String) -> Unit,
    onOpenTrash: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val context = LocalContext.current
    val viewModel: SpaceDetailViewModel = viewModel(key = spaceId) {
        SpaceDetailViewModel(
            spaceRepository = app.container.spaceRepository,
            deviceRepository = app.container.deviceRepository,
            syncRepository = app.container.syncRepository,
            syncEngine = app.container.syncEngine,
            apiClient = app.container.apiClient,
            scheduleSync = { SyncScheduler.enqueue(app, force = true) },
            spaceId = spaceId
        )
    }
    val space by viewModel.space.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val bindingPending by viewModel.bindingPending.collectAsStateWithLifecycle()
    val fileCounts by viewModel.fileCounts.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val joinRequests by viewModel.joinRequests.collectAsStateWithLifecycle()
    val resolvingRequestId by viewModel.resolvingRequestId.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val membershipChanging by viewModel.membershipChanging.collectAsStateWithLifecycle()
    val syncStats by viewModel.syncStats.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val conflictLocalSizes by viewModel.conflictLocalSizes.collectAsStateWithLifecycle()
    val resolvingTaskId by viewModel.resolvingTaskId.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val canManageMembers by viewModel.canManageMembers.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var changeTarget by remember { mutableStateOf<LocalFolderMapping?>(null) }
    var manageMembers by rememberSaveable { mutableStateOf(false) }
    var exitConfirm by rememberSaveable { mutableStateOf(false) }
    // 冲突保留选择：task to keepLocal，确认后才执行
    var conflictChoice by remember { mutableStateOf<Pair<LocalSyncTask, Boolean>?>(null) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    val thisDeviceIsMember = members.any { it.deviceId == viewModel.currentDeviceId }

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = changeTarget
        changeTarget = null
        if (uri != null && target != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val label = DocumentFile.fromTreeUri(context, uri)?.name
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "已选目录"
                viewModel.changeDirectory(target, uri.toString(), label)
            } catch (e: SecurityException) {
                viewModel.showMessage("目录授权失败：系统未授予持久读写权限，请重新选择")
            } catch (e: Exception) {
                viewModel.showMessage("目录授权失败：${e.message ?: "无法读取所选目录"}")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpacePageHeader(
                title = space?.name ?: "空间详情",
                onBack = onBack,
                actions = {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新空间")
                        }
                    }
                    IconButton(onClick = { onEditSpace(spaceId) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑空间")
                    }
                }
            )

            if (space == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator() else Text("未找到该空间，请返回刷新")
                }
            } else {
                val latestScan = mappings.mapNotNull { it.lastScannedAt }.maxOrNull()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (canManageMembers && joinRequests.isNotEmpty()) {
                        item { SectionHeader(title = "加入申请") }
                        items(joinRequests, key = { "join-${it.id}" }) { request ->
                            JoinRequestCard(
                                request = request,
                                resolving = resolvingRequestId == request.id,
                                onApprove = { viewModel.resolveJoinRequest(request.id, true) },
                                onReject = { viewModel.resolveJoinRequest(request.id, false) }
                            )
                        }
                    }
                    item {
                        Column {
                            when {
                                mappings.isNotEmpty() -> {
                                    PrimaryButton(
                                        text = if (syncStats.runningTasks > 0) "正在同步…" else "立即同步",
                                        onClick = viewModel::syncNow,
                                        enabled = syncStats.runningTasks == 0,
                                        leadingIcon = Icons.Filled.Sync
                                    )
                                    Text(
                                        text = "最近扫描：${formatIsoTime(latestScan)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                }
                                bindingPending -> {
                                    PrimaryButton(
                                        text = "等待主设备同意",
                                        onClick = viewModel::refresh,
                                        leadingIcon = Icons.Filled.HourglassEmpty
                                    )
                                    Text(
                                        text = "绑定申请已提交，同意后将自动完成目录绑定；点此刷新状态",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                }
                                else -> {
                                    PrimaryButton(
                                        text = "绑定本地目录",
                                        onClick = { onAddMapping(spaceId) },
                                        leadingIcon = Icons.Filled.Add
                                    )
                                    Text(
                                        text = "绑定目录后即可开始同步",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "本地目录",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            // 右侧两个图标入口：回收站（左）、云端存储（右）
                            IconButton(onClick = { onOpenTrash(spaceId) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "回收站",
                                    tint = OnSurfaceMuted
                                )
                            }
                            IconButton(onClick = {
                                showStorage = true
                                viewModel.loadStorageStats()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Cloud,
                                    contentDescription = "云端存储情况",
                                    tint = PrimaryBlue
                                )
                            }
                        }
                    }
                    if (mappings.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                EmptyStateView(
                                    icon = Icons.Filled.Folder,
                                    title = "尚未绑定本地目录",
                                    subtitle = "点击上方「绑定本地目录」，该目录的结构会完整同步到空间"
                                )
                            }
                        }
                    } else {
                        items(mappings, key = { it.id }) { mapping ->
                            FolderMappingCard(
                                mapping = mapping,
                                fileCount = fileCounts.firstOrNull { it.mappingId == mapping.id }?.fileCount ?: 0,
                                onOpenFiles = { onOpenMapping(mapping.id) },
                                onChangeDirectory = {
                                    changeTarget = mapping
                                    directoryLauncher.launch(null)
                                }
                            )
                        }
                    }
                    item {
                        SectionHeader(
                            title = "参与设备",
                            actionText = when {
                                canManageMembers -> "管理"
                                thisDeviceIsMember -> "退出空间"
                                else -> null
                            },
                            onAction = when {
                                canManageMembers -> ({ manageMembers = true })
                                thisDeviceIsMember -> ({ exitConfirm = true })
                                else -> null
                            }
                        )
                    }
                    if (members.isEmpty()) {
                        item { Text("暂无成员信息，请刷新重试", color = OnSurfaceMuted) }
                    } else {
                        items(members, key = { it.deviceId }) { member ->
                            MemberRow(member = member, thisDeviceId = viewModel.currentDeviceId)
                        }
                    }
                    item { SectionHeader(title = "同步任务") }
                    item {
                        SpaceTaskSummaryCard(
                            stats = syncStats,
                            onRetryAllFailed = viewModel::retryAllFailed
                        )
                    }
                    if (tasks.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                EmptyStateView(
                                    icon = Icons.Filled.Sync,
                                    title = "暂无待办任务",
                                    subtitle = "该空间待上传、待下载、待删除的任务都会在此列出"
                                )
                            }
                        }
                    } else {
                        items(tasks, key = { it.id }) { task ->
                            val conflict = app.container.syncEngine.isResolvablePathConflict(task)
                            SyncTaskCard(
                                task = task,
                                conflictResolvable = conflict,
                                localSize = conflictLocalSizes[task.id],
                                resolving = resolvingTaskId == task.id,
                                onRetry = { viewModel.retryTask(task) },
                                onCancel = { viewModel.cancelTask(task) },
                                onKeepLocal = { conflictChoice = task to true },
                                onKeepCloud = { conflictChoice = task to false }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (manageMembers) {
        MemberManageDialog(
            devices = devices,
            members = members,
            changingDeviceId = membershipChanging,
            onCheckedChange = viewModel::setMembership,
            onDismiss = { manageMembers = false }
        )
    }

    if (showStorage) {
        StorageStatsSheet(
            stats = storageStats,
            onDismiss = { showStorage = false }
        )
    }

    if (exitConfirm) {
        ConfirmBottomSheet(
            title = "退出空间",
            message = "确定让本机退出该空间吗？退出后本机不再参与同步。",
            confirmText = "退出空间",
            onConfirm = {
                viewModel.exitSpace()
                exitConfirm = false
            },
            onDismiss = { exitConfirm = false }
        )
    }

    conflictChoice?.let { (task, keepLocal) ->
        val localSizeText = conflictLocalSizes[task.id]?.let { formatFileSize(it) } ?: "未知"
        val cloudSizeText = formatFileSize(task.size)
        ConfirmBottomSheet(
            title = if (keepLocal) "保留本地版本" else "保留云端版本",
            message = buildString {
                append(task.remoteRelativePath.substringAfterLast('/'))
                append('\n')
                append("云端 $cloudSizeText · 本地 $localSizeText\n\n")
                if (keepLocal) {
                    append("确认后将以本地内容覆盖云端同名文件（云端当前版本将被删除替代），保持两端严格一致。")
                } else {
                    append("确认后本地当前文件将移入应用回收站，随后重新下载云端版本，保持两端严格一致。")
                }
            },
            confirmText = "确认保留",
            onConfirm = {
                viewModel.resolveConflict(task, keepLocal)
                conflictChoice = null
            },
            onDismiss = { conflictChoice = null }
        )
    }
}

/** 云端存储情况弹层：活动文件与回收站的数量/占用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageStatsSheet(
    stats: StorageStatsUiState?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "云端存储情况",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            when {
                stats == null || stats.loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                stats.error != null -> {
                    Text(
                        text = stats.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ErrorRed,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                else -> {
                    StorageStatsRow("云端文件", "${stats.activeFileCount} 个 · ${formatFileSize(stats.activeSize)}")
                    StorageStatsRow("回收站", "${stats.trashCount} 个 · ${formatFileSize(stats.trashSize)}")
                    Text(
                        text = "回收站中的文件保留 30 天后自动清除；还原或彻底删除可在文件列表操作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageStatsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun JoinRequestCard(
    request: JoinRequestDto,
    resolving: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = PrimaryBlue)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${request.deviceName} 申请加入空间",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "申请时间：${formatIsoTime(request.requestedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onReject, enabled = !resolving) { Text("拒绝") }
                TextButton(onClick = onApprove, enabled = !resolving) { Text("同意") }
            }
        }
    }
}

@Composable
private fun FolderMappingCard(
    mapping: LocalFolderMapping,
    fileCount: Int,
    onOpenFiles: () -> Unit,
    onChangeDirectory: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenFiles)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = PrimaryBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = mapping.localPathLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MappingStatusChip(mapping.status)
            }
            Text(
                text = "空间根目录 · $fileCount 个文件 · ${if (mapping.onlyMedia) "仅照片和视频" else "全部文件"}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "上次扫描：${formatIsoTime(mapping.lastScannedAt)} · ${if (mapping.lastScanComplete) "完整" else "未完成"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (mapping.lastScanComplete) OnSurfaceMuted else ErrorRed,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onChangeDirectory) { Text("更改目录") }
            }
        }
    }
}

/** 该空间的任务概况：待处理只统计从未执行的任务（等待执行/等待重试），进行中与失败单独计数。 */
@Composable
private fun SpaceTaskSummaryCard(
    stats: com.photosync.app.data.local.SyncTaskStats,
    onRetryAllFailed: () -> Unit
) {
    val pending = stats.pendingUploads + stats.pendingDownloads + stats.pendingMutations
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = when {
                    stats.failedTasks > 0 -> "${stats.failedTasks} 个任务失败，可重试"
                    stats.runningTasks > 0 -> "正在执行 ${stats.runningTasks} 个任务"
                    pending > 0 -> "还有 $pending 个变更待同步"
                    else -> "没有待处理的同步任务"
                },
                fontWeight = FontWeight.SemiBold,
                color = if (stats.failedTasks > 0) ErrorRed else PrimaryBlue
            )
            Text(
                text = "上传 ${stats.pendingUploads} · 下载 ${stats.pendingDownloads} · 删除 ${stats.pendingDeletes} · 重命名 ${stats.pendingRenames}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "成功 ${stats.completedTasks} · 进行中 ${stats.runningTasks} · 失败 ${stats.failedTasks}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (stats.failedTasks > 0) {
                TextButton(
                    onClick = onRetryAllFailed,
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text("全部重试", color = PrimaryBlue)
                }
            }
        }
    }
}

/** 单个同步任务卡片：状态、进度、错误与重试/取消操作；路径冲突失败时提供 本地/云端 保留选择。 */
@Composable
private fun SyncTaskCard(
    task: LocalSyncTask,
    conflictResolvable: Boolean = false,
    localSize: Long? = null,
    resolving: Boolean = false,
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onKeepLocal: () -> Unit = {},
    onKeepCloud: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TaskPresentation.typeIcon(task.type),
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = task.remoteRelativePath,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = TaskPresentation.stateIcon(task.state),
                    contentDescription = null,
                    tint = TaskPresentation.stateColor(task.state),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = TaskPresentation.stateLabel(task.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskPresentation.stateColor(task.state),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = buildString {
                    append(TaskPresentation.typeLabel(task.type))
                    if (task.size > 0) append(" · ${formatFileSize(task.size)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "更新于 ${TaskPresentation.formatTime(task.updatedAt)} · 尝试 ${task.attempt} 次",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (task.state == SyncTaskState.RUNNING && task.size > 0) {
                Text(
                    text = "${formatFileSize(task.bytesTransferred)} / ${formatFileSize(task.size)} · ${TaskPresentation.progressText(task.bytesTransferred, task.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryBlue,
                    modifier = Modifier.padding(top = 8.dp)
                )
                LinearProgressIndicator(
                    progress = { (task.bytesTransferred.toFloat() / task.size.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
            if (conflictResolvable) {
                Text(
                    text = "云端 ${formatFileSize(task.size)} · 本地 ${localSize?.let { formatFileSize(it) } ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryBlue,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            val error = TaskPresentation.errorText(task.error)
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (conflictResolvable || TaskPresentation.canRetry(task.state) || TaskPresentation.canCancel(task.state)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (conflictResolvable) {
                        // 冲突处理按钮靠左：点哪个保留哪个，删掉另外一个，保证两端严格一致
                        TextButton(
                            onClick = onKeepLocal,
                            enabled = !resolving
                        ) { Text("本地", color = PrimaryBlue) }
                        TextButton(
                            onClick = onKeepCloud,
                            enabled = !resolving
                        ) { Text("云端", color = PrimaryBlue) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (TaskPresentation.canCancel(task.state)) {
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    if (TaskPresentation.canRetry(task.state)) {
                        TextButton(onClick = onRetry) { Text("重试", color = PrimaryBlue) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: SpaceMemberDto, thisDeviceId: String?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Smartphone, contentDescription = null, tint = PrimaryBlue)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.deviceName + if (member.deviceId == thisDeviceId) "（本机）" else "",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${platformLabel(member.platform)} · ${roleLabel(member.role)} · 最近在线 ${formatIsoTime(member.lastSeenAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }
            Icon(
                imageVector = if (member.online) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                contentDescription = null,
                tint = if (member.online) SuccessGreen else OnSurfaceMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (member.online) "在线" else "离线",
                style = MaterialTheme.typography.bodySmall,
                color = if (member.online) SuccessGreen else OnSurfaceMuted
            )
        }
    }
}

@Composable
private fun MemberManageDialog(
    devices: List<LocalDevice>,
    members: List<SpaceMemberDto>,
    changingDeviceId: String?,
    onCheckedChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理参与设备") },
        text = {
            Column {
                Text("勾选本账号下要加入空间的设备；取消勾选会将设备移出空间。", color = OnSurfaceMuted)
                Spacer(Modifier.height(12.dp))
                if (devices.isEmpty()) {
                    Text("设备列表为空，请先在设备管理页刷新或注册设备")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(devices, key = { it.id }) { device ->
                            val joined = members.any { it.deviceId == device.id }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = joined,
                                    onCheckedChange = { onCheckedChange(device.id, it) },
                                    enabled = changingDeviceId == null
                                )
                                Column {
                                    Text(device.deviceName + if (device.isThisDevice) "（本机）" else "")
                                    Text(
                                        if (changingDeviceId == device.id) "正在更新…" else device.platform,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun platformLabel(platform: String): String =
    if (platform.equals("android", ignoreCase = true)) "Android" else platform

private fun roleLabel(role: String): String = when (role) {
    "owner" -> "所有者"
    "admin" -> "管理员"
    "member" -> "成员"
    "viewer" -> "只读"
    else -> role
}

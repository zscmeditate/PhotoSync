package com.photosync.app.ui.spaces

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.LocalFolderMapping
import com.photosync.app.data.local.LocalSyncTask
import com.photosync.app.data.local.SyncTaskState
import com.photosync.app.data.remote.JoinRequestDto
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.AppSnackbarHost
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.SectionHeader
import com.photosync.app.ui.components.StatTile
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.util.formatIsoTime

@Composable
fun SpaceDetailScreen(
    spaceId: String,
    onBack: () -> Unit,
    onEditSpace: (String) -> Unit,
    onAddMapping: (String) -> Unit,
    onOpenMapping: (String) -> Unit,
    onOpenTrash: (String) -> Unit,
    onOpenActivityLog: (String) -> Unit
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
            scheduleSync = { SyncScheduler.enqueue(app, force = true, spaceId = spaceId) },
            spaceId = spaceId,
            appScope = app.appScope
        )
    }
    val space by viewModel.space.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val bindingPending by viewModel.bindingPending.collectAsStateWithLifecycle()
    val fileCounts by viewModel.fileCounts.collectAsStateWithLifecycle()
    val joinRequests by viewModel.joinRequests.collectAsStateWithLifecycle()
    val resolvingRequestId by viewModel.resolvingRequestId.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val syncStats by viewModel.syncStats.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val taskFailureDetail by viewModel.taskFailureDetail.collectAsStateWithLifecycle()
    val detailTask by viewModel.detailTask.collectAsStateWithLifecycle()
    val resolvingTaskId by viewModel.resolvingTaskId.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val backgroundSyncing by viewModel.backgroundSyncing.collectAsStateWithLifecycle()
    val resolvingAllDuplicateNames by viewModel.resolvingAllDuplicateNames.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val logCount by viewModel.logCount.collectAsStateWithLifecycle()
    val canManageMembers by viewModel.canManageMembers.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val membershipChanging by viewModel.membershipChanging.collectAsStateWithLifecycle()
    val pendingDeletionCount by viewModel.pendingDeletionCount.collectAsStateWithLifecycle()
    val resolvingBulkDelete by viewModel.resolvingBulkDelete.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var changeTarget by remember { mutableStateOf<LocalFolderMapping?>(null) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    var contentTab by rememberSaveable { mutableStateOf(SpaceContentTab.LOCAL_DIRECTORY) }
    var showMemberManage by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    // 任务筛选：null = 全部；再点一次已选中的筛选可取消
    var taskFilter by rememberSaveable { mutableStateOf<TaskFilter?>(null) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showResolveMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 进入页面即拉取云端存储统计，供「回收站 / 云端目录」卡片展示数量与大小
    LaunchedEffect(spaceId) { viewModel.loadStorageStats() }

    val filteredTasks = remember(tasks, taskFilter) {
        tasks
            .filter { task ->
                when (taskFilter) {
                    null -> true
                    TaskFilter.RUNNING -> task.state == SyncTaskState.PENDING ||
                        task.state == SyncTaskState.RUNNING || task.state == SyncTaskState.RETRY
                    TaskFilter.SUCCESS -> task.state == SyncTaskState.COMPLETED
                    TaskFilter.FAILED -> task.state == SyncTaskState.FAILED
                }
            }
            .sortedWith(
                compareBy<LocalSyncTask> { task ->
                    when (task.state) {
                        SyncTaskState.RUNNING -> 0
                        SyncTaskState.RETRY -> 1
                        SyncTaskState.PENDING -> 2
                        SyncTaskState.FAILED -> 3
                        else -> 4
                    }
                }.thenByDescending { it.updatedAt }
            )
    }
    val runningCount = remember(tasks) {
        tasks.count {
            it.state == SyncTaskState.PENDING || it.state == SyncTaskState.RUNNING || it.state == SyncTaskState.RETRY
        }
    }
    val successCount = remember(tasks) { tasks.count { it.state == SyncTaskState.COMPLETED } }
    val failedCount = remember(tasks) { tasks.count { it.state == SyncTaskState.FAILED } }
    val duplicateNameFailedCount = remember(tasks) {
        tasks.count {
            it.state == SyncTaskState.FAILED &&
                it.error?.substringBefore('：') == "FILE_ALREADY_EXISTS"
        }
    }

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
                    // 刷新中/同步中不显示转圈，仅禁用按钮避免重复点击
                    IconButton(
                        onClick = { viewModel.refresh(); viewModel.runQueuedTasks() },
                        enabled = !loading && !syncing && !backgroundSyncing
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新并执行待办任务")
                    }
                    // 只有管理员（owner/admin；本账号主设备自动具备管理员角色）才可以重命名空间
                    if (space?.role == "admin" || space?.role == "owner") {
                        IconButton(onClick = { onEditSpace(spaceId) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑空间")
                        }
                    }
                }
            )

            if (space == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator() else Text("未找到该空间，请返回刷新")
                }
            } else {
                val latestScan = mappings.mapNotNull { it.lastScannedAt }.maxOrNull()
                // 「同步任务」标题位置：加入申请 + 同步按钮 + 快捷入口 + 内容 Tab + 当前 Tab 内容
                val joinSectionSize = if (canManageMembers && joinRequests.isNotEmpty()) 1 + joinRequests.size else 0
                val contentItemCount = if (contentTab == SpaceContentTab.PARTICIPATING_DEVICES || mappings.isEmpty()) 1 else mappings.size
                val taskHeaderIndex = joinSectionSize + 3 + contentItemCount
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    // 任务区至少一屏高：筛选后内容很少时「同步任务」标题仍能滚到最顶部，不会被拉回
                    val taskSectionMinHeight = maxHeight
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                                    // 手动同步与后台同步（启动/周期/列表页）都算忙碌，扫描阶段也包含在内
                                    val busy = syncing || backgroundSyncing
                                    PrimaryButton(
                                        text = when {
                                            // 按钮只给简洁状态，具体阶段（扫描/上传/下载/应用云端变更）不体现在文案里
                                            busy || syncStats.runningTasks > 0 -> "正在同步…"
                                            else -> "立即同步"
                                        },
                                        onClick = viewModel::syncNow,
                                        enabled = !busy && syncStats.runningTasks == 0,
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
                                    if (pendingDeletionCount > 0) {
                                        Spacer(Modifier.height(10.dp))
                                        PendingBulkDeleteCard(
                                            count = pendingDeletionCount,
                                            busy = resolvingBulkDelete || busy,
                                            onConfirm = viewModel::confirmPendingDeletions,
                                            onKeep = viewModel::keepPendingDeletionFiles
                                        )
                                    }
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
                        // 上方大字为数量（回收站/云端目录右侧灰色小字为总大小），下方为「小图标 + 名称」
                        val stats = storageStats?.takeIf { !it.loading }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatTile(
                                title = stats?.let { "${it.trashCount}" } ?: "—",
                                trailing = stats?.let { formatFileSize(it.trashSize) },
                                label = "回收站",
                                icon = Icons.Filled.Delete,
                                tint = PrimaryBlue,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenTrash(spaceId) }
                            )
                            StatTile(
                                title = stats?.let { "${it.activeFileCount}" } ?: "—",
                                trailing = stats?.let { formatFileSize(it.activeSize) },
                                label = "云端目录",
                                icon = Icons.Filled.Cloud,
                                tint = PrimaryBlue,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showStorage = true
                                    viewModel.loadStorageStats()
                                }
                            )
                            StatTile(
                                title = "$logCount",
                                label = "同步日志",
                                icon = Icons.Filled.History,
                                tint = PrimaryBlue,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenActivityLog(spaceId) }
                            )
                        }
                    }
                    item {
                        // 成员明细尚未加载时先用空间自带的设备数，避免显示为 0
                        val shownMemberCount = if (members.isEmpty()) space?.memberCount ?: 0 else members.size
                        ScrollableTabRow(
                            selectedTabIndex = contentTab.ordinal,
                            modifier = Modifier.fillMaxWidth(),
                            edgePadding = 0.dp,
                            containerColor = Color.Transparent,
                            contentColor = PrimaryBlue,
                            divider = {}
                        ) {
                            SpaceContentTab.entries.forEach { tab ->
                                val selected = contentTab == tab
                                Tab(
                                    selected = selected,
                                    onClick = { contentTab = tab },
                                    selectedContentColor = PrimaryBlue,
                                    unselectedContentColor = OnSurfaceMuted,
                                    text = {
                                        Text(
                                            text = when (tab) {
                                                SpaceContentTab.LOCAL_DIRECTORY -> "本地目录"
                                                SpaceContentTab.PARTICIPATING_DEVICES -> "参与设备($shownMemberCount)"
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }
                    if (contentTab == SpaceContentTab.LOCAL_DIRECTORY) {
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
                                val counts = fileCounts.firstOrNull { it.mappingId == mapping.id }
                                FolderMappingCard(
                                    mapping = mapping,
                                    fileCount = counts?.fileCount ?: 0,
                                    totalSize = counts?.totalSize ?: 0L,
                                    onOpenFiles = { onOpenMapping(mapping.id) },
                                    onChangeDirectory = {
                                        changeTarget = mapping
                                        directoryLauncher.launch(null)
                                    }
                                )
                            }
                        }
                    } else {
                        item {
                            ParticipantDevicesCard(
                                members = members,
                                thisDeviceId = viewModel.currentDeviceId,
                                canManageMembers = canManageMembers && space?.role != "none",
                                onManageMembers = { showMemberManage = true },
                                onExitSpace = { showExitConfirm = true }
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("同步任务", style = MaterialTheme.typography.titleMedium)
                            if (failedCount > 0) {
                                TextButton(
                                    onClick = viewModel::retryAllFailed,
                                    enabled = resolvingTaskId == null && !resolvingAllDuplicateNames,
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("全部重试", color = PrimaryBlue, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    item {
                        SpaceTaskSummaryCard(stats = syncStats)
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TaskFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = taskFilter == filter,
                                    onClick = {
                                        taskFilter = if (taskFilter == filter) null else filter
                                        // 每次点筛选都把「同步任务」标题滚到最顶部，直接看到筛选结果
                                        scope.launch { listState.animateScrollToItem(taskHeaderIndex) }
                                    },
                                    label = {
                                        val count = when (filter) {
                                            TaskFilter.RUNNING -> runningCount
                                            TaskFilter.SUCCESS -> successCount
                                            TaskFilter.FAILED -> failedCount
                                        }
                                        val label = when (filter) {
                                            TaskFilter.RUNNING -> "进行中"
                                            TaskFilter.SUCCESS -> "成功"
                                            TaskFilter.FAILED -> "失败"
                                        }
                                        Text("$label $count", style = MaterialTheme.typography.bodySmall)
                                    }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (taskFilter == TaskFilter.FAILED && failedCount > 0) {
                                Box {
                                    IconButton(
                                        onClick = { showResolveMenu = true },
                                        enabled = resolvingTaskId == null && !resolvingAllDuplicateNames
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "批量处理失败任务",
                                            tint = PrimaryBlue
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showResolveMenu,
                                        onDismissRequest = { showResolveMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("全部云端优先") },
                                            enabled = duplicateNameFailedCount > 0,
                                            onClick = {
                                                showResolveMenu = false
                                                viewModel.resolveAllDuplicateNamesKeepCloud()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("大文件优先") },
                                            onClick = {
                                                showResolveMenu = false
                                                viewModel.resolveAllLargerWins()
                                            }
                                        )
                                    }
                                }
                            }
                            if (taskFilter == TaskFilter.SUCCESS && successCount > 0) {
                                TextButton(
                                    onClick = { showClearConfirm = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("清空", color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    if (filteredTasks.size <= 20) {
                        // 内容较少：任务区整体撑到至少一屏高，避免筛选后「同步任务」标题被拉回；
                        // 数量小可以直接组合。超过 20 条时内容本身就超过一屏，继续用惰性列表。
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = taskSectionMinHeight),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (filteredTasks.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        EmptyStateView(
                                            icon = Icons.Filled.Sync,
                                            title = "暂无同步任务",
                                            subtitle = "该空间的同步任务会在此列出，可按 进行中 / 成功 / 失败 筛选"
                                        )
                                    }
                                } else {
                                    filteredTasks.forEachIndexed { index, task ->
                                        SyncTaskCard(
                                            order = index + 1,
                                            task = task,
                                            resolving = resolvingTaskId == task.id,
                                            onCancel = { viewModel.cancelTask(task) },
                                            onShowDetail = { viewModel.openTaskFailureDetail(task) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(filteredTasks, key = { _, task -> task.id }) { index, task ->
                            SyncTaskCard(
                                order = index + 1,
                                task = task,
                                resolving = resolvingTaskId == task.id,
                                onCancel = { viewModel.cancelTask(task) },
                                onShowDetail = { viewModel.openTaskFailureDetail(task) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                }
            }
        }
        AppSnackbarHost(hostState = snackbarHostState)
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空已完成任务") },
            text = { Text("确定要清空这 $successCount 条已完成的同步记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearCompleted()
                }) {
                    Text("清空", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showStorage) {
        StorageStatsSheet(
            stats = storageStats,
            onDismiss = { showStorage = false }
        )
    }

    if (showMemberManage) {
        MemberManageDialog(
            devices = devices,
            members = members,
            changingDeviceId = membershipChanging,
            onCheckedChange = viewModel::setMembership,
            onDismiss = { showMemberManage = false }
        )
    }

    if (showExitConfirm) {
        ConfirmBottomSheet(
            title = "退出空间",
            message = "确定让本机退出「${space?.name ?: "该空间"}」？本机目录绑定会被解除，云端文件不受影响。",
            confirmText = "退出空间",
            onConfirm = {
                viewModel.exitSpace()
                showExitConfirm = false
            },
            onDismiss = { showExitConfirm = false }
        )
    }

    taskFailureDetail?.let { detail ->
        val state = detailTask?.state
        FailureDetailSheet(
            detail = detail,
            busy = resolvingTaskId != null,
            canResolve = state == SyncTaskState.FAILED,
            onKeepLocal = { viewModel.resolveTaskFile(FileResolveAction.KEEP_LOCAL) },
            onKeepCloud = { viewModel.resolveTaskFile(FileResolveAction.KEEP_CLOUD) },
            onDeleteBoth = { viewModel.resolveTaskFile(FileResolveAction.DELETE_BOTH) },
            onDismiss = viewModel::closeTaskFailureDetail,
            onRetry = if (state != null && TaskPresentation.canRetry(state)) {
                { viewModel.retryDetailTask() }
            } else {
                null
            },
            onCancelTask = if (state != null && TaskPresentation.canCancel(state)) {
                { viewModel.cancelDetailTask() }
            } else {
                null
            }
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
    totalSize: Long,
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
                text = "空间根目录 · $fileCount 个文件 · ${formatFileSize(totalSize)} · ${if (mapping.onlyMedia) "仅照片和视频" else "全部文件"}",
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

/** 该空间的待处理任务统计：按操作类型分列展示，纯统计无操作入口。 */
@Composable
private fun SpaceTaskSummaryCard(
    stats: com.photosync.app.data.local.SyncTaskStats
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryStatCell("上传", stats.pendingUploads, Modifier.weight(1f), stats.pendingUploadBytes)
            SummaryStatCell("下载", stats.pendingDownloads, Modifier.weight(1f), stats.pendingDownloadBytes)
            SummaryStatCell("删除", stats.pendingDeletes, Modifier.weight(1f))
            SummaryStatCell("重命名", stats.pendingRenames, Modifier.weight(1f))
        }
    }
}

/** 单列统计：第一行「个数 + 标签」，传入 sizeBytes 时第二行显示文件大小合计（如 45.6 MB）。 */
@Composable
private fun SummaryStatCell(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryBlue
            )
            Text(
                text = " $label",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        if (sizeBytes != null) {
            Text(
                text = formatFileSize(sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 单个同步任务卡片：状态、进度、错误；进行中任务提供「取消」，其余任务操作收纳到「处理」入口。 */
@Composable
private fun SyncTaskCard(
    order: Int,
    task: LocalSyncTask,
    resolving: Boolean = false,
    onCancel: () -> Unit = {},
    onShowDetail: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左上角自然顺序编号
                Text(
                    text = order.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .background(PrimaryBlue, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = TaskPresentation.typeIcon(task.type),
                    contentDescription = null,
                    tint = TaskPresentation.typeColor(task.type),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TaskPresentation.taskTitle(task),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                text = TaskPresentation.taskPath(task),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
            // 信息行：类型/大小/尝试次数/更新时间，占满一整行
            Text(
                text = buildString {
                    append(TaskPresentation.typeLabel(task.type))
                    append(if (task.size > 0) " ${formatFileSize(task.size)}" else " 大小未知")
                    append(" · 尝试 ${task.attempt} 次")
                    append(" · 更新于 ${TaskPresentation.formatTime(task.updatedAt)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (task.state == SyncTaskState.RUNNING && task.size > 0) {
                Text(
                    text = "${formatFileSize(task.bytesTransferred)} / ${formatFileSize(task.size)} · ${TaskPresentation.progressText(task.bytesTransferred, task.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryBlue,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // 进行中：进度条在左（压缩长度），右侧仅「取消」
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { (task.bytesTransferred.toFloat() / task.size.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                        drawStopIndicator = {}
                    )
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(20.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (task.state == SyncTaskState.RUNNING && task.size <= 0) {
                // 大小未知的进行中任务：仅显示「取消」
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (task.state != SyncTaskState.COMPLETED && task.state != SyncTaskState.RUNNING) {
                // 操作行：左侧失败原因（没有则留空），右侧「处理」入口（重试/取消收纳在处理面板内）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TaskPresentation.errorText(task.error) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onShowDetail,
                        enabled = !resolving,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text("处理", color = PrimaryBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 同步任务筛选维度；null 表示全部。 */
private enum class SpaceContentTab { LOCAL_DIRECTORY, PARTICIPATING_DEVICES }

enum class TaskFilter { RUNNING, SUCCESS, FAILED }

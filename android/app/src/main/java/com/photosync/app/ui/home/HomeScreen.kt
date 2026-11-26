package com.photosync.app.ui.home

import android.os.SystemClock

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.photosync.app.BuildConfig
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.local.SyncTaskStats
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.data.sync.SpaceTaskCompletion
import com.photosync.app.ui.components.AppSnackbarHost
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.SectionHeader
import com.photosync.app.ui.components.SyncStatus
import com.photosync.app.ui.components.SyncStatusChip
import com.photosync.app.ui.devices.DeviceListScreen
import com.photosync.app.ui.navigation.Screen
import com.photosync.app.ui.settings.SettingsScreen
import com.photosync.app.ui.spaces.SpaceCard
import com.photosync.app.ui.spaces.SpaceListScreen
import com.photosync.app.ui.spaces.SpaceListViewModel
import com.photosync.app.ui.spaces.formatFileSize
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

enum class HomeTab(
    val label: String,
    val icon: ImageVector
) {
    OVERVIEW("首页", Icons.Filled.Home),
    FILES("同步空间", Icons.Filled.Folder),
    DEVICES("设备", Icons.Filled.Smartphone),
    SETTINGS("设置", Icons.Filled.Settings)
}

/** 首页：底部导航 4 Tab（首页 / 同步空间 / 设备 / 设置） */
@Composable
fun HomeScreen(navController: NavHostController, onLoggedOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.OVERVIEW) }
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val spaceViewModel: SpaceListViewModel = viewModel {
        SpaceListViewModel(app.container.spaceRepository, app.container.deviceRepository)
    }

    // 切 Tab 到首页或同步空间时，自动从服务端拉一次最新空间列表（30 秒节流在 refresh 内部）。
    LaunchedEffect(tab) {
        if (tab == HomeTab.OVERVIEW || tab == HomeTab.FILES) {
            spaceViewModel.refresh()
        }
    }

    // 从子页面（SpaceDetail / AddMapping 等）pop 返回 Home 时，立即强制刷新一次空间列表，
    // 以便看到刚申请/审批/绑定后的最新状态。
    DisposableEffect(navController, tab) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (destination.route == Screen.Home.route) {
                spaceViewModel.refresh(force = true)
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // 首页是导航根页面：连续两次返回（间隔 2 秒内）才退出应用，第一次给轻量提示。
    val activityContext = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var lastBackAt by rememberSaveable { mutableStateOf(0L) }
    BackHandler {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt in 1..BACK_EXIT_INTERVAL_MS) {
            (activityContext as? ComponentActivity)?.finishAffinity()
        } else {
            lastBackAt = now
            scope.launch { snackbarHostState.showSnackbar("再按一次退出应用") }
        }
    }

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HomeTab.entries.forEach { item ->
                        val selected = tab == item
                        val tint = if (selected) PrimaryBlue else OnSurfaceMuted
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { tab = item },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier.size(21.dp)
                            )
                            Spacer(Modifier.height(0.dp))
                            Text(
                                text = item.label,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                HomeTab.OVERVIEW -> HomeOverviewTab(
                    viewModel = spaceViewModel,
                    onOpenSpace = { navController.navigate(Screen.SpaceDetail(it).route) },
                    onCreateSpace = { navController.navigate(Screen.SpaceCreate.route) },
                    onJoinSpace = { navController.navigate(Screen.JoinSpace.route) },
                    onShowAll = { tab = HomeTab.FILES },
                    onOpenActivity = { navController.navigate(Screen.ActivityLog(null).route) }
                )
                HomeTab.FILES -> SpaceListScreen(
                    viewModel = spaceViewModel,
                    onOpenSpace = { navController.navigate(Screen.SpaceDetail(it).route) },
                    onCreateSpace = { navController.navigate(Screen.SpaceCreate.route) },
                    onJoinSpace = { navController.navigate(Screen.JoinSpace.route) },
                    onEditSpace = { navController.navigate(Screen.SpaceEdit(it).route) }
                )
                HomeTab.DEVICES -> DeviceListScreen()
                HomeTab.SETTINGS -> SettingsScreen(
                    onLoggedOut = onLoggedOut
                )
            }
            AppSnackbarHost(hostState = snackbarHostState, bottomPadding = 16.dp)
        }
    }
}

/** 两次返回触发退出的最大间隔（毫秒）。 */
private const val BACK_EXIT_INTERVAL_MS = 2000L

@Composable
private fun HomeOverviewTab(
    viewModel: SpaceListViewModel,
    onOpenSpace: (String) -> Unit,
    onCreateSpace: () -> Unit,
    onJoinSpace: () -> Unit,
    onShowAll: () -> Unit,
    onOpenActivity: () -> Unit
) {
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val canCreateSpace by viewModel.canCreateSpace.collectAsStateWithLifecycle()
    val abnormalSpaceIds = mappings.filter {
        it.status == MappingStatus.ERROR || it.status == MappingStatus.ACCESS_REVOKED
    }.map { it.spaceId }.toSet()
    val hasAbnormalMapping = abnormalSpaceIds.isNotEmpty()
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    // 上传/下载/扫描都会建立会话（taskProgress 有 key），排队等待的空间在 syncingSpaceIds 中
    val taskProgress by app.container.syncEngine.taskProgress.collectAsStateWithLifecycle()
    val syncingSpaceIds by app.container.syncEngine.syncingSpaceIds.collectAsStateWithLifecycle()
    val activeSpaceId = taskProgress.entries.maxByOrNull { it.value.startedAt }?.key
        ?: syncingSpaceIds.firstOrNull()
    val activeSpaceName = activeSpaceId?.let { id -> spaces.firstOrNull { it.id == id }?.name }
    // 与通知栏同源：同步Worker执行中或已发布进度通知时，也算正在同步
    val notifier = app.container.syncProgressNotifier
    val foregroundNotification by notifier.foreground.collectAsStateWithLifecycle()
    val workerActive by notifier.workerActive.collectAsStateWithLifecycle()
    val isSyncing = activeSpaceId != null || foregroundNotification != null || workerActive
    // 当前同步空间的剩余任务统计：与通知栏一致，只展示上传/下载的剩余量与大小
    val activeStatsFlow: Flow<SyncTaskStats?> = remember(activeSpaceId) {
        val id = activeSpaceId
        if (id == null) flowOf(null) else app.container.syncRepository.observeStats(id)
    }
    val activeStats by activeStatsFlow.collectAsStateWithLifecycle(initialValue = null)
    val activeSession = activeSpaceId?.let { taskProgress[it] }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 顶部标题（统一标题行高度 48dp，与带图标按钮的 Tab 页一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "同步精灵",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 总览卡片：左侧整体同步状态（灰底 + 绿色勾选，设计图样式），右侧当前正在同步的空间
        val syncRotation = if (isSyncing) {
            val transition = rememberInfiniteTransition(label = "homeSyncRotation")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing)
                ),
                label = "syncAngle"
            ).value
        } else {
            0f
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OverviewCard(
                icon = if (hasAbnormalMapping) Icons.Filled.SyncProblem else Icons.Filled.CheckCircle,
                tint = when {
                    spaces.isEmpty() -> OnSurfaceMuted
                    hasAbnormalMapping -> WarningOrange
                    else -> SuccessGreen
                },
                title = when {
                    spaces.isEmpty() -> "暂无同步空间"
                    hasAbnormalMapping -> "部分目录需要处理"
                    else -> "同步正常"
                },
                subtitle = when {
                    spaces.isEmpty() -> "还没有同步空间"
                    mappings.isEmpty() -> "未绑定本地目录"
                    hasAbnormalMapping -> "有目录需要处理"
                    else -> "状态正常"
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            OverviewCard(
                icon = Icons.Filled.Sync,
                tint = if (isSyncing) PrimaryBlue else OnSurfaceMuted,
                title = when {
                    activeSpaceId != null -> activeSpaceName ?: "同步空间"
                    isSyncing -> "同步空间"
                    else -> "当前无同步"
                },
                subtitle = if (isSyncing) {
                    syncRemainingText(activeStats, activeSession)
                } else {
                    "等待同步任务"
                },
                iconModifier = Modifier.graphicsLayer { rotationZ = syncRotation },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // 统计卡片（与上方总览卡片统一样式：左侧图标 + 右侧两行文字）
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OverviewCard(
                icon = Icons.Filled.Folder,
                tint = PrimaryBlue,
                title = "${spaces.size}",
                subtitle = "同步空间",
                titleColor = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            OverviewCard(
                icon = Icons.Filled.Sync,
                tint = PrimaryBlue,
                title = "${mappings.size}",
                subtitle = "本机同步空间",
                titleColor = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                title = "我的同步空间",
                modifier = Modifier.weight(1f),
                actionText = if (spaces.isNotEmpty()) "全部" else null,
                onAction = if (spaces.isNotEmpty()) onShowAll else null
            )
            IconButton(onClick = onJoinSpace) {
                Icon(Icons.Filled.GroupAdd, contentDescription = "加入空间")
            }
            if (canCreateSpace) {
                IconButton(onClick = onCreateSpace) {
                    Icon(Icons.Filled.Add, contentDescription = "创建空间", tint = PrimaryBlue)
                }
            }
        }
        if (spaces.isEmpty()) {
            SurfaceCard {
                EmptyStateView(
                    icon = Icons.Filled.Folder,
                    title = "暂无同步空间",
                    subtitle = if (canCreateSpace) {
                        "点击 + 创建空间，或加入已有空间，再选择本地目录"
                    } else {
                        "加入已有空间并选择本地目录；创建空间需要使用主设备"
                    }
                )
            }
        } else {
            spaces.take(3).forEachIndexed { index, space ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                }
                SpaceCard(
                    space = space,
                    hasError = space.id in abnormalSpaceIds,
                    onClick = { onOpenSpace(space.id) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        // 48dp 行高与"我的同步空间"标题行一致，保证两段标题文字起始高度相同
        Row(
            modifier = Modifier.height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                title = "最近活动",
                actionText = "查看全部",
                onAction = onOpenActivity,
                modifier = Modifier.weight(1f)
            )
        }
        val recentTasks by app.container.syncRepository.observeRecentTasks(3)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (recentTasks.isEmpty()) {
            SurfaceCard {
                EmptyStateView(
                    icon = Icons.Filled.History,
                    title = "同步活动",
                    subtitle = "查看本机最近的上传、下载、删除与重命名记录"
                )
            }
        } else {
            SurfaceCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    recentTasks.forEachIndexed { index, task ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenActivity)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = TaskPresentation.typeIcon(task.type)
                            val tint = TaskPresentation.typeColor(task.type)
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = TaskPresentation.taskTitle(task),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${TaskPresentation.typeLabel(task.type)} · ${TaskPresentation.formatTime(task.updatedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceMuted
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 右卡片副标题：与通知栏一致，只显示上传/下载的剩余任务数与剩余大小。 */
private fun syncRemainingText(stats: SyncTaskStats?, session: SpaceTaskCompletion?): String {
    if (stats == null || session == null) return "正在同步…"
    val uploadRemainingBytes = (stats.pendingUploadBytes - session.activeUploadBytes).coerceAtLeast(0L)
    val downloadRemainingBytes = (stats.pendingDownloadBytes - session.activeDownloadBytes).coerceAtLeast(0L)
    val lines = buildList {
        if (stats.pendingUploads > 0) {
            add("上传剩余 ${stats.pendingUploads} · ${formatFileSize(uploadRemainingBytes)}")
        }
        if (stats.pendingDownloads > 0) {
            add("下载剩余 ${stats.pendingDownloads} · ${formatFileSize(downloadRemainingBytes)}")
        }
    }
    return if (lines.isEmpty()) "正在同步…" else lines.joinToString("\n")
}

/** 顶部卡片统一样式：左侧图标，右侧上下两行文字（水平居中、垂直居中）。 */
@Composable
private fun OverviewCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    titleColor: Color = tint
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(28.dp)
                .then(iconModifier)
        )
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        content()
    }
}

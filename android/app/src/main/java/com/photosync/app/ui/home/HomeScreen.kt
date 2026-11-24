package com.photosync.app.ui.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.AccountCircle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.MappingStatus
import com.photosync.app.data.local.SyncTaskType
import com.photosync.app.ui.components.ConfirmBottomSheet
import com.photosync.app.ui.components.EmptyStateView
import com.photosync.app.ui.components.IconBadge
import com.photosync.app.ui.components.SectionHeader
import com.photosync.app.ui.components.SyncStatus
import com.photosync.app.ui.components.SyncStatusChip
import com.photosync.app.ui.devices.DeviceListScreen
import com.photosync.app.ui.navigation.Screen
import com.photosync.app.ui.settings.SettingsScreen
import com.photosync.app.ui.spaces.SpaceCard
import com.photosync.app.ui.spaces.SpaceListScreen
import com.photosync.app.ui.spaces.SpaceListViewModel
import com.photosync.app.ui.tasks.TaskPresentation
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.WarningOrange

enum class HomeTab(
    val label: String,
    val icon: ImageVector
) {
    OVERVIEW("首页", Icons.Filled.Home),
    FILES("文件", Icons.Filled.Folder),
    DEVICES("设备", Icons.Filled.Smartphone),
    SETTINGS("设置", Icons.Filled.Settings)
}

/** 首页：底部导航 4 Tab（首页 / 文件 / 设备 / 设置） */
@Composable
fun HomeScreen(navController: NavHostController, onLoggedOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.OVERVIEW) }
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val spaceViewModel: SpaceListViewModel = viewModel {
        SpaceListViewModel(app.container.spaceRepository, app.container.deviceRepository)
    }

    // 首页是导航根页面，返回手势不再直接退出应用，改为弹出二次确认。
    var exitConfirm by rememberSaveable { mutableStateOf(false) }
    val activityContext = LocalContext.current
    BackHandler {
        exitConfirm = true
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
                    onOpenActivity = { navController.navigate(Screen.ActivityLog.route) }
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
                    onLoggedOut = onLoggedOut,
                    onOpenActivity = { navController.navigate(Screen.ActivityLog.route) }
                )
            }
        }
    }

    if (exitConfirm) {
        ConfirmBottomSheet(
            title = "退出 PhotoSync",
            message = "确定要退出应用吗？退出后后台同步仍会按计划运行。",
            confirmText = "退出应用",
            onConfirm = {
                (activityContext as? androidx.activity.ComponentActivity)?.finishAffinity()
            },
            onDismiss = { exitConfirm = false }
        )
    }
}

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 顶部标题 + 头像
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "多设备同步",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "手机 ↔ 你的服务器 ↔ 手机",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }
            IconBadge(
                icon = Icons.Filled.AccountCircle,
                size = 44,
                background = Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan))
            )
        }

        // 总览状态卡片（灰底 + 绿色勾选，设计图样式）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (hasAbnormalMapping) Icons.Filled.SyncProblem else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = when {
                    spaces.isEmpty() -> OnSurfaceMuted
                    hasAbnormalMapping -> WarningOrange
                    else -> SuccessGreen
                },
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    spaces.isEmpty() -> "暂无同步空间"
                    hasAbnormalMapping -> "部分目录需要处理"
                    else -> "同步正常"
                },
                color = when {
                    spaces.isEmpty() -> OnSurfaceMuted
                    hasAbnormalMapping -> WarningOrange
                    else -> SuccessGreen
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            val summaryHint = when {
                spaces.isEmpty() -> "创建空间并添加本地目录后，这里会显示空间与映射摘要。"
                mappings.isEmpty() -> "本机尚未绑定本地目录"
                hasAbnormalMapping -> "有目录授权失效或扫描异常，请进入空间详情处理"
                else -> null
            }
            if (summaryHint != null) {
                Text(
                    text = summaryHint,
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "待同步任务可在各空间详情页查看",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 统计卡片（设计图：两列灰底小卡）
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                icon = Icons.Filled.Folder,
                value = "${spaces.size}",
                label = "同步空间",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.Sync,
                value = "${mappings.size}",
                label = "本机同步空间",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(24.dp))
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
            spaces.take(3).forEach { space ->
                SpaceCard(
                    space = space,
                    hasError = space.id in abnormalSpaceIds,
                    onClick = { onOpenSpace(space.id) }
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader(
            title = "最近活动",
            actionText = "查看全部",
            onAction = onOpenActivity
        )
        val app = LocalContext.current.applicationContext as PhotoSyncApp
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
                            val (icon, tint) = when (task.type) {
                                SyncTaskType.UPLOAD -> Icons.Filled.CloudUpload to PrimaryBlue
                                SyncTaskType.DOWNLOAD -> Icons.Filled.CloudDownload to SuccessGreen
                                SyncTaskType.DELETE -> Icons.Filled.Delete to ErrorRed
                                else -> Icons.Filled.Edit to WarningOrange
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.remoteRelativePath.substringAfterLast('/'),
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
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryBlue,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
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

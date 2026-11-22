package com.photosync.app.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.MappingStatus
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
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue

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
        SpaceListViewModel(app.container.spaceRepository)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryBlue,
                            selectedTextColor = PrimaryBlue,
                            indicatorColor = PrimaryBlue.copy(alpha = 0.12f),
                            unselectedIconColor = OnSurfaceMuted,
                            unselectedTextColor = OnSurfaceMuted
                        )
                    )
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
                    onShowAll = { tab = HomeTab.FILES },
                    onOpenActivity = { navController.navigate(Screen.ActivityLog.route) }
                )
                HomeTab.FILES -> SpaceListScreen(
                    viewModel = spaceViewModel,
                    onOpenSpace = { navController.navigate(Screen.SpaceDetail(it).route) },
                    onCreateSpace = { navController.navigate(Screen.SpaceCreate.route) },
                    onEditSpace = { navController.navigate(Screen.SpaceEdit(it).route) }
                )
                HomeTab.DEVICES -> DeviceListScreen()
                HomeTab.SETTINGS -> SettingsScreen(
                    onLoggedOut = onLoggedOut,
                    onOpenTaskCenter = { navController.navigate(Screen.TaskCenter.route) },
                    onOpenActivity = { navController.navigate(Screen.ActivityLog.route) }
                )
            }
        }
    }
}

@Composable
private fun HomeOverviewTab(
    viewModel: SpaceListViewModel,
    onOpenSpace: (String) -> Unit,
    onCreateSpace: () -> Unit,
    onShowAll: () -> Unit,
    onOpenActivity: () -> Unit
) {
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val abnormalSpaceIds = mappings.filter {
        it.status == MappingStatus.ERROR || it.status == MappingStatus.ACCESS_REVOKED
    }.map { it.spaceId }.toSet()
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

        // 总览状态卡片（渐变）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan)))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CloudQueue,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (spaces.isEmpty()) "暂无同步空间" else "${spaces.size} 个同步空间",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (spaces.isEmpty()) {
                    "创建空间并添加本地目录后，这里会显示空间与映射摘要。"
                } else {
                    "共 ${spaces.sumOf { it.mappingCount }} 个目录映射"
                },
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "上传 / 下载任务可在「设置 → 任务中心」查看",
                    color = Color.White.copy(alpha = 0.90f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                title = "我的同步空间",
                modifier = Modifier.weight(1f),
                actionText = if (spaces.isNotEmpty()) "全部" else null,
                onAction = if (spaces.isNotEmpty()) onShowAll else null
            )
            IconButton(onClick = onCreateSpace) {
                Icon(Icons.Filled.Add, contentDescription = "创建空间", tint = PrimaryBlue)
            }
        }
        if (spaces.isEmpty()) {
            SurfaceCard {
                EmptyStateView(
                    icon = Icons.Filled.Folder,
                    title = "暂无同步空间",
                    subtitle = "点击 + 创建空间，再添加一个或多个本地目录"
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
        SurfaceCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenActivity)
            ) {
                EmptyStateView(
                    icon = Icons.Filled.History,
                    title = "同步活动",
                    subtitle = "查看本机最近的上传、下载、删除与冲突处理记录"
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        content()
    }
}

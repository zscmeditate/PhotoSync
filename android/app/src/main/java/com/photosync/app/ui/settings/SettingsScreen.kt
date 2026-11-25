package com.photosync.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.diagnostics.DiagnosticsExporter
import com.photosync.app.data.prefs.SyncPreferences
import com.photosync.app.data.sync.SyncScheduler
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import kotlinx.coroutines.launch

/** 设置页：账号/服务器、同步调度与诊断入口。 */
@Composable
fun SettingsScreen(
    onLoggedOut: () -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel {
        SettingsViewModel(
            app.container.serverRepository,
            app.container.secureTokenStore,
            app.container.authRepository,
            app.container.deviceRepository,
            app.container.spaceRepository,
            app.container.settingsRepository,
            rescheduleSync = { SyncScheduler.reschedule(app) },
            cancelSync = { SyncScheduler.cancel(app) }
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is SettingsState.LoggedOut) {
            onLoggedOut()
        }
    }

    val ready = state as? SettingsState.Ready

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "账号与服务器",
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingRow(
                        icon = Icons.Filled.Cloud,
                        label = "服务器地址",
                        value = ready?.data?.serverUrl ?: "加载中…"
                    )
                    Spacer(Modifier.height(14.dp))
                    SettingRow(
                        icon = Icons.Filled.Person,
                        label = "账号信息",
                        value = ready?.data?.email ?: "加载中…",
                        secondary = ready?.data?.displayName
                    )
                }
            }

            Text(
                text = "同步设置",
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        icon = Icons.Filled.Sync,
                        label = "自动后台同步",
                        description = "按间隔在后台检查并同步变更",
                        checked = ready?.data?.autoSyncEnabled ?: true,
                        onCheckedChange = viewModel::setAutoSync
                    )
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        icon = Icons.Filled.Info,
                        label = "仅 Wi-Fi 同步",
                        description = "关闭后允许使用移动数据",
                        checked = ready?.data?.wifiOnly ?: true,
                        onCheckedChange = viewModel::setWifiOnly
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "后台同步间隔",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SyncPreferences.INTERVAL_OPTIONS.forEach { minutes ->
                            FilterChip(
                                selected = (ready?.data?.intervalMinutes ?: 15) == minutes,
                                onClick = { viewModel.setInterval(minutes) },
                                enabled = ready?.data?.autoSyncEnabled == true,
                                label = { Text(if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时") }
                            )
                        }
                    }
                    Text(
                        text = "Android 后台最小间隔为 15 分钟；实时变更仍会由服务器推送触发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                text = "退出登录",
                onClick = { viewModel.logout() },
                leadingIcon = Icons.AutoMirrored.Filled.Logout
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "退出登录将清除本机保存的 Token",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    value: String,
    secondary: String? = null,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryBlue,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryBlue,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

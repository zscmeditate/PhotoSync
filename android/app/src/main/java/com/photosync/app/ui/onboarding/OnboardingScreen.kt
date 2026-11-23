package com.photosync.app.ui.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photosync.app.PhotoSyncApp
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.components.IconBadge
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.first

/**
 * 引导页：介绍中心服务器模式（手机 ↔ 服务器 ↔ 手机）。
 */
@Composable
fun OnboardingScreen(
    onStartConfig: () -> Unit,
    onHaveAccount: () -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val serverUrl by produceState(initialValue = "") {
        value = app.container.settingsRepository.serverBaseUrl.first()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "多设备文件同步",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "中心服务器模式 · 数据存放在你自己的服务器",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted
        )

        Spacer(Modifier.height(32.dp))
        SyncDiagram()

        Spacer(Modifier.height(32.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BulletItem("文件通过你的服务器在多台手机间同步")
            BulletItem("支持为每台设备选择多个本地目录")
            BulletItem("设备离线期间的变更，上线后自动补齐")
            BulletItem("冲突保留双方版本，删除先进回收站")
        }

        Spacer(Modifier.height(36.dp))
        PrimaryButton(
            text = "开始配置",
            onClick = onStartConfig,
            leadingIcon = Icons.Filled.Dns
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = {
                // 已有账号也必须先有服务器地址
                if (serverUrl.isBlank()) onStartConfig() else onHaveAccount()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = "我已有账号",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryBlue
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SyncDiagram() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        DeviceNode(icon = Icons.Filled.Smartphone, label = "手机 A")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = "双向同步",
                tint = AccentCyan,
                modifier = Modifier.size(28.dp)
            )
        }
        DeviceNode(icon = Icons.Filled.Dns, label = "服务器", highlight = true)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = "双向同步",
                tint = AccentCyan,
                modifier = Modifier.size(28.dp)
            )
        }
        DeviceNode(icon = Icons.Filled.Smartphone, label = "手机 B")
    }
}

@Composable
private fun DeviceNode(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlight: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(
            icon = icon,
            size = 60,
            background = if (highlight) {
                Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFF94A3B8), Color(0xFF64748B)))
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) PrimaryBlue else OnSurfaceMuted,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BulletItem(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

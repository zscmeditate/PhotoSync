package com.photosync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.ErrorRed
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import com.photosync.app.ui.theme.RadiusButton
import com.photosync.app.ui.theme.RadiusCard
import com.photosync.app.ui.theme.SuccessGreen
import com.photosync.app.ui.theme.SurfaceSoft
import com.photosync.app.ui.theme.WarningOrange

/**
 * 统计卡片：上方一行大字（可选右侧灰色小字，如文件总大小），下方一行「小图标 + 小字」。
 */
@Composable
fun StatTile(
    title: String,
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    titleColor: Color = tint,
    iconModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusCard.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!trailing.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = trailing,
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(14.dp)
                    .then(iconModifier)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 醒目主按钮（纯蓝大按钮，设计图样式） */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(RadiusButton.dp))
            .background(if (enabled) PrimaryBlue else Color(0xFFC9D4E2))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 次要描边按钮 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(RadiusButton.dp)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 与 AppTextField 一致的输入框配色：灰底、聚焦蓝边（设计图样式） */
@Composable
fun appTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = SurfaceSoft,
    unfocusedContainerColor = SurfaceSoft,
    disabledContainerColor = SurfaceSoft,
    focusedBorderColor = PrimaryBlue,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent
)

/** 统一输入框：灰底圆角（设计图样式），带可选标签与前后图标 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            placeholder = placeholder?.let { { Text(it, color = OnSurfaceMuted) } },
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = OnSurfaceMuted) } },
            trailingIcon = trailingIcon,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            shape = RoundedCornerShape(RadiusButton.dp),
            colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 空状态：图标 + 标题 + 说明（文字与图标同时表达，不依赖颜色） */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OnSurfaceMuted,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center
        )
    }
}

enum class SyncStatus { OK, SYNCING, PAUSED, ERROR, EMPTY }

/** 同步状态：图标 + 文字（不只靠颜色） */
@Composable
fun SyncStatusChip(status: SyncStatus, modifier: Modifier = Modifier) {
    val (icon, text, color) = when (status) {
        SyncStatus.OK -> Triple(Icons.Filled.CheckCircle, "已同步", SuccessGreen)
        SyncStatus.SYNCING -> Triple(Icons.Filled.PhonelinkRing, "同步中", PrimaryBlue)
        SyncStatus.PAUSED -> Triple(Icons.Filled.PauseCircle, "已暂停", WarningOrange)
        SyncStatus.ERROR -> Triple(Icons.Filled.SyncProblem, "同步异常", ErrorRed)
        SyncStatus.EMPTY -> Triple(Icons.Filled.Info, "暂无同步空间", OnSurfaceMuted)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** 设备在线状态：图标 + 文字 */
@Composable
fun OnlineStatusRow(online: Boolean, lastSeenText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (online) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
            contentDescription = null,
            tint = if (online) SuccessGreen else OnSurfaceMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (online) "在线" else "离线",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (online) SuccessGreen else OnSurfaceMuted
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            tint = OnSurfaceMuted,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = lastSeenText,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted
        )
    }
}

/** 错误横幅：图标 + 文案 */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorRed.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            color = ErrorRed,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 通用卡片容器 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    background: Brush? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusCard.dp))
            .background(brush = background ?: Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)
            ))
    ) {
        content()
    }
}

/** 二次确认底部弹层（ConfirmBottomSheet） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmBottomSheet(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(RadiusButton.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Text(confirmText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Text("取消", fontSize = 16.sp)
            }
        }
    }
}

/** 圆形图标徽标（用于页面顶部 / 设备头像） */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    background: Brush = Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan)),
    tint: Color = Color.White,
    size: Int = 48
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.5).dp)
        )
    }
}

/** 区块标题行 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(actionText, color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

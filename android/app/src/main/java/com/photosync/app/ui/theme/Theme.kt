package com.photosync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---- 设计令牌（依据“文件同步应用界面设计展示”浅色设计图） ----
// 主蓝：按钮、链接、激活态；灰底：卡片/输入框；文字：近黑标题 + 灰色辅助信息。
val PrimaryBlue = Color(0xFF2F6BFF)
val PrimaryBlueDark = Color(0xFF1E4FD1)
val AccentCyan = Color(0xFF4DA3FF)
val SuccessGreen = Color(0xFF22C55E)
val WarningOrange = Color(0xFFFF9500)
val WarningYellow = Color(0xFFF5B400)
val ErrorRed = Color(0xFFF04438)
val SurfaceSoft = Color(0xFFF2F4F7)
val OnSurfaceMuted = Color(0xFF8A94A6)
val TextPrimary = Color(0xFF1A1D26)

// 卡片圆角 12dp、按钮圆角 12dp、缩略图圆角 10dp（在组件/页面中引用）
val RadiusCard = 12
val RadiusButton = 12
val RadiusThumb = 10

val BrandGradient = Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan))
val BrandGradientSoft = Brush.verticalGradient(
    listOf(PrimaryBlue.copy(alpha = 0.10f), AccentCyan.copy(alpha = 0.05f))
)

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF1FF),
    onPrimaryContainer = PrimaryBlueDark,
    secondary = AccentCyan,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = TextPrimary,
    surface = Color.White,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = OnSurfaceMuted,
    outlineVariant = Color(0xFFE5E9F0),
    error = ErrorRed,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FA8FF),
    onPrimary = Color(0xFF0B2545),
    secondary = Color(0xFF6EC6FF),
    background = Color(0xFF10151F),
    surface = Color(0xFF171E2B),
    onSurface = Color(0xFFE8ECF4),
    error = Color(0xFFFF7B6E)
)

private val AppTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun PhotoSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}

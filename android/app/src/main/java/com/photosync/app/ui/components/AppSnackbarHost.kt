package com.photosync.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局统一的 Toast：底部居中、圆角矩形、深色半透明背景，宽度随文字自适应。
 */
@Composable
fun BoxScope.AppSnackbarHost(
    hostState: SnackbarHostState,
    bottomPadding: Dp = 64.dp
) {
    SnackbarHost(
        hostState = hostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = bottomPadding),
        snackbar = { data ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = data.visuals.message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
}

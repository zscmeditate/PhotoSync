package com.photosync.app.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.local.SyncDatabase
import com.photosync.app.data.prefs.SecureTokenStore
import com.photosync.app.data.prefs.SettingsRepository
import com.photosync.app.ui.navigation.Screen
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SplashState {
    data object Checking : SplashState
    data class Navigate(val route: String) : SplashState
}

/**
 * 启动页：初始化 Room、检查服务器地址与登录 Token，决定去向。
 */
class SplashViewModel(
    private val database: SyncDatabase,
    private val settingsRepository: SettingsRepository,
    private val tokenStore: SecureTokenStore
) : ViewModel() {

    private val _state = MutableStateFlow<SplashState>(SplashState.Checking)
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 初始化 Room：打开数据库并预热 DAO
            database.localDeviceDao().observeAll().first()
            val serverUrl = settingsRepository.getServerBaseUrl()
            _state.value = SplashState.Navigate(
                when {
                    serverUrl.isBlank() -> Screen.Onboarding.route
                    tokenStore.isLoggedIn -> Screen.Home.route
                    else -> Screen.Login.route
                }
            )
        }
    }
}

@Composable
fun SplashScreen(onNavigate: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: SplashViewModel = viewModel {
        SplashViewModel(
            app.container.database,
            app.container.settingsRepository,
            app.container.secureTokenStore
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        (state as? SplashState.Navigate)?.let { onNavigate(it.route) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "多设备文件同步",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "让所有设备文件保持一致",
            style = MaterialTheme.typography.bodyLarge,
            color = OnSurfaceMuted
        )

        Spacer(Modifier.height(48.dp))
        Text(
            text = "正在检查登录状态",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .width(220.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

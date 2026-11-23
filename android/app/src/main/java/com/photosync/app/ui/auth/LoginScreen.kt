package com.photosync.app.ui.auth

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.app.PhotoSyncApp
import com.photosync.app.data.repository.DEFAULT_DEVICE_NAME
import com.photosync.app.ui.components.AppTextField
import com.photosync.app.ui.components.ErrorBanner
import com.photosync.app.ui.components.PrimaryButton
import com.photosync.app.ui.theme.AccentCyan
import com.photosync.app.ui.theme.OnSurfaceMuted
import com.photosync.app.ui.theme.PrimaryBlue
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoRegister: () -> Unit,
    onGoServerConfig: () -> Unit
) {
    val app = LocalContext.current.applicationContext as PhotoSyncApp
    val viewModel: LoginViewModel = viewModel {
        LoginViewModel(app.container.authRepository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(DEFAULT_DEVICE_NAME) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state is AuthFormState.Success) {
            onLoginSuccess()
            viewModel.consumeState()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            AuthHeroBadge()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "登录",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "登录后自动注册本机设备",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted
            )

            Spacer(Modifier.height(28.dp))
            AppTextField(
                value = email,
                onValueChange = { email = it },
                label = "邮箱",
                leadingIcon = Icons.Filled.Email,
                keyboardType = KeyboardType.Email
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                leadingIcon = Icons.Filled.Lock,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = "本机设备名称",
                leadingIcon = Icons.Filled.PhoneAndroid
            )

            val errorState = state as? AuthFormState.Error
            if (errorState != null) {
                Spacer(Modifier.height(16.dp))
                ErrorBanner(message = errorState.message)
            }

            Spacer(Modifier.height(28.dp))
            PrimaryButton(
                text = "登录",
                onClick = { viewModel.login(email, password, deviceName) },
                enabled = state !is AuthFormState.Loading
            )

            if (state is AuthFormState.Loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onGoRegister) {
                    Text("注册账号", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = {
                    scope.launch {
                        snackbarHostState.showSnackbar("忘记密码功能将在后续版本提供")
                    }
                }) {
                    Text("忘记密码", color = OnSurfaceMuted)
                }
            }

            TextButton(onClick = onGoServerConfig) {
                Text("修改服务器地址", color = OnSurfaceMuted)
            }
            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
internal fun AuthHeroBadge() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(PrimaryBlue, AccentCyan))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
    }
}

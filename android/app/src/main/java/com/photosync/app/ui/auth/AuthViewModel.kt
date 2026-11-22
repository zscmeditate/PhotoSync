package com.photosync.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.app.data.remote.ApiResult
import com.photosync.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthFormState {
    data object Idle : AuthFormState
    data object Loading : AuthFormState
    data object Success : AuthFormState
    data class Error(val message: String) : AuthFormState
}

internal fun validateEmail(email: String): String? = when {
    email.isBlank() -> "请输入邮箱"
    !email.contains("@") || email.startsWith("@") || email.endsWith("@") -> "邮箱格式不正确"
    else -> null
}

internal fun validatePassword(password: String): String? = when {
    password.isBlank() -> "请输入密码"
    password.length < 6 -> "密码至少 6 位"
    else -> null
}

internal fun validateDisplayName(name: String): String? =
    if (name.isBlank()) "请输入显示名" else null

/** 登录页 ViewModel */
class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<AuthFormState>(AuthFormState.Idle)
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun login(email: String, password: String, deviceName: String) {
        val error = validateEmail(email) ?: validatePassword(password)
        if (error != null) {
            _state.value = AuthFormState.Error(error)
            return
        }
        viewModelScope.launch {
            _state.value = AuthFormState.Loading
            when (val result = authRepository.login(email.trim(), password)) {
                is ApiResult.Success -> {
                    // 登录成功后自动注册本机设备
                    authRepository.registerThisDevice(deviceName)
                    _state.value = AuthFormState.Success
                }
                is ApiResult.Failure -> _state.value = AuthFormState.Error(result.message)
            }
        }
    }

    fun consumeState() {
        _state.value = AuthFormState.Idle
    }
}

/** 注册页 ViewModel */
class RegisterViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<AuthFormState>(AuthFormState.Idle)
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun register(email: String, password: String, displayName: String, deviceName: String) {
        val error = validateEmail(email)
            ?: validatePassword(password)
            ?: validateDisplayName(displayName)
        if (error != null) {
            _state.value = AuthFormState.Error(error)
            return
        }
        viewModelScope.launch {
            _state.value = AuthFormState.Loading
            when (val result = authRepository.register(email.trim(), password, displayName.trim())) {
                is ApiResult.Success -> {
                    // 注册成功后自动注册本机设备
                    authRepository.registerThisDevice(deviceName)
                    _state.value = AuthFormState.Success
                }
                is ApiResult.Failure -> _state.value = AuthFormState.Error(result.message)
            }
        }
    }

    fun consumeState() {
        _state.value = AuthFormState.Idle
    }
}

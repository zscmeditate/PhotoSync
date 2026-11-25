package com.photosync.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Token 与账号信息的安全存储：EncryptedSharedPreferences + Android Keystore。
 * 保存 accessToken / refreshToken 及当前账号、本机设备信息。
 */
class SecureTokenStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "photosync_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userDisplayName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    /**
     * 本机设备 ID：已登记时返回服务端下发的 ID；尚未登记（例如卸载重装后数据被清空）
     * 时返回由 [Settings.Secure.ANDROID_ID] 派生的稳定 UUID，服务端按该 ID 建记录并长期复用，
     * 因此同一台手机重装后不会再新增重复设备。
     */
    var localDeviceId: String?
        get() = prefs.getString(KEY_LOCAL_DEVICE_ID, null) ?: stableDeviceId
        set(value) = prefs.edit().putString(KEY_LOCAL_DEVICE_ID, value).apply()

    /** 已登记到服务端的设备 ID；未登记时为 null，避免同步请求引用尚不存在的设备 */
    fun currentDeviceId(): String? = prefs.getString(KEY_LOCAL_DEVICE_ID, null)

    /**
     * 由 ANDROID_ID 派生的稳定 UUID：同一设备、同一签名、同一系统用户下跨卸载重装不变，
     * 仅在恢复出厂设置或换机时变化。
     */
    private val stableDeviceId: String? by lazy {
        runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { UUID.nameUUIDFromBytes("photosync-device:$it".toByteArray()).toString() }
        }.getOrNull()
    }

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrBlank()

    fun saveSession(userId: String, email: String, displayName: String, access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_NAME, displayName)
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .apply()
    }

    fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * 退出登录时清理会话：清除 token 与用户信息，但保留 [localDeviceId]。
     * 这样同一台手机重新登录后会复用同一条设备记录（不会反复新增设备），
     * 且服务端已在登出时清除其空间绑定，重新登录后需手动加入空间。
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_NAME = "user_name"
        const val KEY_LOCAL_DEVICE_ID = "local_device_id"
    }
}

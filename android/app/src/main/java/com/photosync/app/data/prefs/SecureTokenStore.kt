package com.photosync.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaDrm
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Token 与账号信息的安全存储：EncryptedSharedPreferences + Android Keystore。
 * 保存 accessToken / refreshToken 及当前账号、本机设备信息。
 */
class SecureTokenStore(context: Context) {

    private val appContext = context.applicationContext

    /** 派生出的稳定设备 ID 缓存；解析失败时不缓存，以便下次重试 */
    private var cachedStableDeviceId: String? = null

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
     * 时返回由硬件标识派生的稳定 UUID，服务端按该 ID 建记录并长期复用，
     * 因此同一台手机重装后不会再新增重复设备。
     */
    var localDeviceId: String?
        get() = prefs.getString(KEY_LOCAL_DEVICE_ID, null) ?: resolveStableDeviceId()
        set(value) = prefs.edit().putString(KEY_LOCAL_DEVICE_ID, value).apply()

    /** 已登记到服务端的设备 ID；未登记时为 null，避免同步请求引用尚不存在的设备 */
    fun currentDeviceId(): String? = prefs.getString(KEY_LOCAL_DEVICE_ID, null)

    /**
     * 由设备硬件标识（Widevine/MediaDrm 设备唯一 ID）派生的稳定 UUID：
     * 由设备 TEE 预置，与 APK 签名/包名、安装状态、系统用户均无关，
     * 卸载重装、覆盖安装、更换签名都不变，绝大多数机型恢复出厂后也不变。
     * 不做任何兜底：读不到时返回 null，宁可登记失败也不用会变化的 ID。
     * 首次派生成功即写入 [KEY_DERIVED_DEVICE_ID]，之后固定复用，允许后续调用重试。
     */
    private fun resolveStableDeviceId(): String? {
        cachedStableDeviceId?.let { return it }
        prefs.getString(KEY_DERIVED_DEVICE_ID, null)?.let {
            cachedStableDeviceId = it
            return it
        }
        val seed = readMediaDrmDeviceId()?.takeIf { it.isNotBlank() } ?: return null
        val derived = UUID.nameUUIDFromBytes("photosync-device:$seed".toByteArray()).toString()
        prefs.edit().putString(KEY_DERIVED_DEVICE_ID, derived).apply()
        cachedStableDeviceId = derived
        return derived
    }

    private fun readMediaDrmDeviceId(): String? = runCatching {
        val drm = MediaDrm(UUID.fromString(WIDEVINE_UUID))
        try {
            Base64.encodeToString(
                drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID),
                Base64.NO_WRAP
            )
        } finally {
            drm.release()
        }
    }.getOrNull()

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
        /** Widevine 的 DRM 方案 UUID，用于读取设备预置的唯一 ID。 */
        const val WIDEVINE_UUID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_NAME = "user_name"
        const val KEY_LOCAL_DEVICE_ID = "local_device_id"

        /** 由硬件标识派生出的 UUID，首次派生后固定复用，直至应用数据被清除。 */
        const val KEY_DERIVED_DEVICE_ID = "derived_device_id"
    }
}

package com.photosync.app.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 读取手机系统里的设备名称（设置 → 关于手机 → 设备名称，也就是蓝牙/热点里显示的名字）。
 * 读不到时退回「厂商 + 机型」。
 */
fun systemDeviceName(context: Context): String {
    val resolver = context.contentResolver
    val candidates = listOf(
        runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull()
    )
    candidates.firstOrNull { !it.isNullOrBlank() }?.let { return it.trim() }
    val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    return "$brand ${Build.MODEL}".trim()
}

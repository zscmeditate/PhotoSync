package com.photosync.app.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 把 ISO8601 时间格式化为本地可读时间；解析失败时原样返回。 */
fun formatIsoTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "未知"
    return try {
        formatter.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
    } catch (e: Exception) {
        try {
            formatter.format(LocalDateTime.parse(iso))
        } catch (e2: Exception) {
            iso
        }
    }
}

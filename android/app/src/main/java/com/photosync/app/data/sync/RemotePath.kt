package com.photosync.app.data.sync

data class RemotePathValidation(
    val normalized: String,
    val error: String? = null
) {
    val isValid: Boolean get() = error == null
}

/** 与服务端一致的远端路径规范化与校验。空间根目录为空字符串。 */
object RemotePath {

    fun normalize(raw: String): String = raw.trim()
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/")

    fun validate(raw: String): RemotePathValidation {
        val normalized = normalize(raw)
        val error = when {
            normalized.any { it.code < 0x20 || it.code == 0x7F } -> "路径不能包含控制字符"
            normalized.split('/').any { it == ".." } -> "路径不能包含 .. 段"
            normalized.split('/').any { it.length > 128 } -> "每个路径段不能超过 128 个字符"
            normalized.length > 512 -> "路径总长度不能超过 512 个字符"
            else -> null
        }
        return RemotePathValidation(normalized, error)
    }
}

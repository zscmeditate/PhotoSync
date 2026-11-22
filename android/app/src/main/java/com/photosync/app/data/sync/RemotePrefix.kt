package com.photosync.app.data.sync

data class RemotePrefixValidation(
    val normalized: String,
    val error: String? = null
) {
    val isValid: Boolean get() = error == null
}

/** 与服务端一致的远端路径规范化、校验和重叠判断。空字符串表示空间根目录。 */
object RemotePrefix {

    fun normalize(raw: String): String = raw.trim()
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/")

    fun validate(raw: String): RemotePrefixValidation {
        val normalized = normalize(raw)
        val error = when {
            normalized.any { it.code < 0x20 || it.code == 0x7F } -> "路径不能包含控制字符"
            normalized.split('/').any { it == ".." } -> "路径不能包含 .. 段"
            normalized.split('/').any { it.length > 128 } -> "每个路径段不能超过 128 个字符"
            normalized.length > 512 -> "路径总长度不能超过 512 个字符"
            else -> null
        }
        return RemotePrefixValidation(normalized, error)
    }

    /** 输入必须已经过规范化；根目录与任意路径重叠。 */
    fun overlaps(a: String, b: String): Boolean = when {
        a == b -> true
        a.isEmpty() || b.isEmpty() -> true
        else -> a.startsWith("$b/") || b.startsWith("$a/")
    }
}

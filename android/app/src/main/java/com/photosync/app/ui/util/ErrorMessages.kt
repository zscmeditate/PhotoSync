package com.photosync.app.ui.util

import com.photosync.app.data.remote.ApiResult

/**
 * 统一错误提示：把服务端错误码与本地同步错误码翻译成中文，
 * 未知错误回退到服务端 message 或调用方提供的默认文案。
 */
object ErrorMessages {

    fun of(failure: ApiResult.Failure): String = when {
        failure.isNetwork -> "无法连接服务器，请检查网络与服务器地址"
        else -> forCode(failure.code, failure.message)
    }

    fun forCode(code: String?, fallback: String? = null): String {
        val text = when (code) {
            null, "" -> null
            "NETWORK_ERROR" -> "无法连接服务器，请检查网络与服务器地址"
            "UNAUTHORIZED" -> "登录状态已失效，请重新登录"
            "INVALID_CREDENTIALS" -> "邮箱或密码不正确"
            "INVALID_REFRESH_TOKEN" -> "登录状态已过期，请重新登录"
            "EMAIL_TAKEN" -> "该邮箱已被注册"
            "DEVICE_NOT_FOUND" -> "设备不存在，请重新注册"
            "DEVICE_NOT_REGISTERED" -> "本机设备尚未注册，请重新登录"
            "INVALID_DEVICE" -> "设备无效或已停用"
            "SPACE_NOT_FOUND" -> "空间不存在或已被删除"
            "SPACE_FORBIDDEN" -> "没有该空间的访问权限"
            "NOT_MEMBER" -> "本机设备不是空间成员"
            "MAPPING_NOT_FOUND" -> "目录映射不存在"
            "MAPPING_EXISTS" -> "该空间已绑定本地目录"
            "UPLOAD_NOT_FOUND" -> "上传会话不存在"
            "UPLOAD_NOT_ACTIVE" -> "上传会话已失效"
            "UPLOAD_INCOMPLETE" -> "上传尚未完成"
            "UPLOAD_INTEGRITY_MISMATCH" -> "上传内容校验失败"
            "PART_CONFLICT" -> "分片内容冲突，请重新上传"
            "FILE_ALREADY_EXISTS" -> "远端路径已存在同名文件"
            "FILE_NOT_FOUND" -> "文件不存在"
            "FILE_IS_DELETED" -> "文件已被删除"
            "VERSION_CONFLICT" -> "文件版本冲突，请先解决冲突"
            "PATH_CONFLICT" -> "远端目标路径已被占用"
            "CONFLICT_NOT_FOUND" -> "冲突记录不存在"
            "CONFLICT_ALREADY_RESOLVED" -> "该冲突已解决"
            "TRASH_NOT_FOUND" -> "回收站条目不存在"
            "BULK_DELETE_CONFIRMATION_REQUIRED" -> "检测到大量删除，需确认后才能继续"
            "OBJECT_NOT_FOUND" -> "远端文件内容已丢失，无法下载"
            "SYNC_CONFLICT" -> "同步状态冲突，请刷新后重试"
            "INTERNAL_ERROR" -> "服务器内部错误，请稍后重试"
            "ACCESS_REVOKED" -> "本地目录授权已失效，请重新授权"
            "LOCAL_FILE_MISSING" -> "本地文件已不存在"
            "LOCAL_PATH_CONFLICT" -> "本地路径已有不同内容"
            "LOCAL_CONTENT_CHANGED" -> "文件在扫描后发生变化，请重新扫描"
            "CONTENT_VERIFICATION" -> "文件内容校验失败"
            "SYNC_CONTRACT_INVALID" -> "同步数据格式不正确"
            else -> null
        }
        return text ?: fallback?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
    }
}

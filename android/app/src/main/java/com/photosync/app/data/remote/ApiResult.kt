package com.photosync.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(
        val code: String?,
        val message: String,
        val isNetwork: Boolean = false
    ) : ApiResult<Nothing>()
}

private val errorJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseErrorMessage(errorBody: ResponseBody?): ApiErrorDetail? {
    val raw = errorBody?.string() ?: return null
    return try {
        errorJson.decodeFromString(ErrorEnvelope.serializer(), raw).error
    } catch (e: Exception) {
        null
    }
}

/** 把 Retrofit Response 统一转换为 ApiResult，解析服务端错误封装。 */
fun <T> Response<T>.toApiResult(): ApiResult<T> {
    return if (isSuccessful) {
        val body = body()
        if (body != null) {
            ApiResult.Success(body)
        } else {
            @Suppress("UNCHECKED_CAST")
            ApiResult.Success(Unit as T)
        }
    } else {
        val detail = parseErrorMessage(errorBody())
        ApiResult.Failure(
            code = detail?.code,
            message = detail?.message ?: "请求失败（HTTP ${code()}）"
        )
    }
}

/** 统一的网络调用包装：捕获 IO / 其他异常。 */
suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        block().toApiResult()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Failure(
            code = "NETWORK_ERROR",
            message = "无法连接服务器，请检查网络与服务器地址",
            isNetwork = true
        )
    } catch (e: Exception) {
        ApiResult.Failure(code = "UNKNOWN_ERROR", message = e.message ?: "发生未知错误")
    }
}

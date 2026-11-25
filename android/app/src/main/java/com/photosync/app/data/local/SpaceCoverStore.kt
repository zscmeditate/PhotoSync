package com.photosync.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 空间头像本地缓存：头像统一由服务端保存，本机只缓存用于列表展示的小图。
 * 缓存文件名带上服务端版本号（coverUpdatedAt），版本变化时自动换新文件。
 */
class SpaceCoverStore(context: Context) {

    private val context = context.applicationContext
    private val coverDir = File(this.context.filesDir, "space_covers")

    /** 把用户选中的照片压缩成适合上传/展示的 JPEG 字节（最长边不超过 [MAX_COVER_PX]）。 */
    suspend fun compressFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        // 相册/照片选择器返回的 Uri 只保证短暂可读，先一次性读出字节，避免多次打开流失败。
        val source = readAllBytes(uri)
        if (source == null || source.isEmpty()) {
            Log.w(TAG, "读取所选照片失败: $uri")
            return@withContext null
        }
        val bitmap = decodeSampled(source)
        if (bitmap == null) {
            Log.w(TAG, "解码所选照片失败（${source.size} bytes）: $uri")
            return@withContext null
        }
        try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                    return@withContext null
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "压缩所选照片失败", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** 按服务端版本号缓存头像，返回本地文件路径；写入失败返回 null。 */
    suspend fun saveBytes(spaceId: String, version: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            coverDir.mkdirs()
            val target = fileFor(spaceId, version)
            try {
                FileOutputStream(target).use { it.write(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "写入头像缓存失败", e)
                target.delete()
                return@withContext null
            }
            removeOtherFiles(spaceId, keep = target)
            target.absolutePath
        }

    /** 该版本的头像是否已缓存。 */
    fun isCached(spaceId: String, version: String): Boolean = fileFor(spaceId, version).isFile

    /** 删除某空间的全部头像缓存（移除头像或空间被删除时调用）。 */
    suspend fun delete(spaceId: String) = withContext(Dispatchers.IO) {
        removeOtherFiles(spaceId, keep = null)
    }

    private fun fileFor(spaceId: String, version: String): File {
        val safeVersion = version.hashCode().toUInt().toString(16)
        return File(coverDir, "$spaceId-$safeVersion.jpg")
    }

    private fun removeOtherFiles(spaceId: String, keep: File?) {
        coverDir.listFiles { file -> file.name.startsWith("$spaceId-") && file != keep }
            ?.forEach { it.delete() }
    }

    private fun readAllBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(TAG, "打开照片数据流失败", e)
        null
    }

    /** 先按采样解码；BitmapFactory 不支持的格式（如部分 HEIF）再用 ImageDecoder 兜底。 */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest > 0 && longest / sample > MAX_COVER_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.w(TAG, "BitmapFactory 解码失败", e)
            null
        }
        if (decoded != null) return decoded
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_COVER_PX) {
                    val scale = MAX_COVER_PX.toFloat() / side
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Log.w(TAG, "ImageDecoder 解码失败", e)
            null
        }
    }

    private companion object {
        const val TAG = "SpaceCoverStore"

        /** 头像只在列表中显示，512px 足够，同时控制上传与存储体积。 */
        const val MAX_COVER_PX = 512
    }
}

package com.photosync.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 空间头像本地缓存：头像统一由服务端保存，本机只缓存用于列表展示的小图。
 * 缓存文件名带上服务端版本号（coverUpdatedAt），版本变化时自动换新文件。
 */
class SpaceCoverStore(context: Context) {

    private val context = context.applicationContext
    private val coverDir = File(this.context.filesDir, "space_covers")

    /** 把用户选中的照片压缩成适合上传/展示的 JPEG 字节（最长边不超过 [MAX_COVER_PX]）。 */
    suspend fun compressFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri) ?: return@withContext null
        try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
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

    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_COVER_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private companion object {
        /** 头像只在列表中显示，512px 足够，同时控制上传与存储体积。 */
        const val MAX_COVER_PX = 512
    }
}

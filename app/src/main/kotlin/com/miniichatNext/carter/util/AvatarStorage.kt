package com.miniichatNext.carter.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AvatarStorage {
    private const val DIR = "avatars"

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun file(context: Context, id: String, ext: String = "jpg"): File =
        File(dir(context), "${id}.${ext.lowercase()}")

    suspend fun saveFromUri(context: Context, id: String, uri: Uri, maxSide: Int = 1024): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                val sample = computeSample(bounds.outWidth, bounds.outHeight, maxSide)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                } ?: return@runCatching null
                val scaled = scaleToMax(decoded, maxSide)
                val target = file(context, id)
                FileOutputStream(target).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                if (scaled !== decoded) decoded.recycle()
                scaled.recycle()
                target.absolutePath
            }.getOrNull()
        }

    suspend fun saveBitmap(context: Context, id: String, bitmap: Bitmap, quality: Int = 92): String =
        withContext(Dispatchers.IO) {
            val target = file(context, id)
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            bitmap.recycle()
            target.absolutePath
        }

    fun delete(context: Context, path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    fun isInAppStorage(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val stableDir = dir(context).absolutePath + File.separator
        return path.startsWith(stableDir)
    }

    fun copyIntoAppStorage(context: Context, path: String?, id: String = newId()): String? {
        if (path.isNullOrBlank()) return path
        val src = File(path)
        if (!src.isFile) return path
        if (isInAppStorage(context, src.absolutePath)) return path
        return runCatching {
            val target = file(context, id)
            src.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrNull() ?: path
    }

    fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).exists()

    /**
     * 清理 filesDir/avatars 下**不再被引用**的文件（换头像后的旧文件、被放弃的裁剪产物）
     *
     * 安全前提：只删除不在[keepPaths]里且最后修改时间早于[olderThanMs]的文件，这样刚生成的裁剪文件（可能还没写进任何assistant）不会被误删
     *
     * @return 实际删除的文件数
     */
    fun pruneOrphans(context: Context, keepPaths: Collection<String>, olderThanMs: Long): Int =
        runCatching {
            val keep = keepPaths.filter { it.isNotBlank() }.toSet()
            val cutoff = System.currentTimeMillis() - olderThanMs
            dir(context).listFiles()?.count { f ->
                f.isFile && f.absolutePath !in keep && f.lastModified() < cutoff && f.delete()
            } ?: 0
        }.getOrDefault(0)

    fun uri(path: String): Uri = File(path).toUri()

    private fun computeSample(w: Int, h: Int, maxSide: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        var cw = w
        var ch = h
        while (cw / 2 >= maxSide || ch / 2 >= maxSide) {
            sample *= 2
            cw /= 2
            ch /= 2
        }
        return sample
    }

    private fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}

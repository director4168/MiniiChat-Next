package com.miniichatNext.carter.data.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import com.miniichatNext.carter.util.newId
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object AvatarStorage {
    private const val DIR = "avatars"

    fun userAvatarFile(context: Context): File =
        AvatarPaths.user(context.filesDir).also { it.parentFile?.mkdirs() }

    fun assistantAvatarFile(context: Context, assistantId: String): File =
        AvatarPaths.assistant(context.filesDir, assistantId).also { it.parentFile?.mkdirs() }

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun file(context: Context, id: String, ext: String = "jpg"): File =
        File(dir(context), "${id}.${ext.lowercase(Locale.ROOT)}")

    fun cropDir(context: Context): File = File(dir(context), ".crop").also { it.mkdirs() }

    suspend fun saveFromUriToPath(
        context: Context,
        uri: Uri,
        targetPath: String,
        maxSide: Int = 1024,
    ): String? = withContext(Dispatchers.IO) {
        saveDecodedToPath(context, uri, targetPath, maxSide)
    }

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
                try {
                    AvatarFileTransfer.write(target) { staged ->
                        FileOutputStream(staged).use { out ->
                            check(scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "Avatar encoding failed" }
                        }
                    }
                    target.absolutePath
                } finally {
                    if (scaled !== decoded) decoded.recycle()
                    scaled.recycle()
                }
            }.getOrNull()
        }

    suspend fun saveBitmap(context: Context, id: String, bitmap: Bitmap, quality: Int = 92): String =
        withContext(Dispatchers.IO) {
            val target = file(context, id)
            try {
                AvatarFileTransfer.write(target) { staged ->
                    FileOutputStream(staged).use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Avatar encoding failed" }
                    }
                }
                target.absolutePath
            } finally { bitmap.recycle() }
        }

    suspend fun saveFileToPath(context: Context, sourcePath: String, targetPath: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(sourcePath) ?: return@runCatching null
                val target = File(targetPath)
                target.parentFile?.mkdirs()
                try {
                    AvatarFileTransfer.write(target) { staged ->
                        FileOutputStream(staged).use { out ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "Avatar encoding failed" }
                        }
                    }
                    target.absolutePath
                } finally { bitmap.recycle() }
            }.getOrNull()
        }

    fun copyToCanonicalPath(sourcePath: String?, targetPath: String): String? {
        if (sourcePath.isNullOrBlank()) return sourcePath
        return runCatching {
            val source = if (sourcePath.startsWith("file:")) File(java.net.URI(sourcePath)) else File(sourcePath)
            if (!source.isFile) return sourcePath
            AvatarFileTransfer.copy(source, File(targetPath)).absolutePath
        }.getOrDefault(sourcePath)
    }

    /** 调用方只回收本次裁剪产物，正式资源的移除由配置保存决定。 */
    fun delete(context: Context, path: String) {
        AvatarFileCleanup.deleteTemporary(cropDir(context), path)
    }

    fun isInAppStorage(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            val file = if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
            file.canonicalPath.startsWith(dir(context).canonicalPath + File.separator)
        }.getOrDefault(false)
    }

    fun copyIntoAppStorage(context: Context, path: String?, id: String = newId()): String? {
        if (path.isNullOrBlank()) return path
        val src = File(path)
        if (!src.isFile) return path
        if (isInAppStorage(context, src.absolutePath)) return path
        return runCatching {
            val target = file(context, id)
            AvatarFileTransfer.copy(src, target)
            target.absolutePath
        }.getOrNull() ?: path
    }

    fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).exists()

    /** 仅清理专用目录中的过期裁剪文件，旧版avatars根目录也完整保留。 */
    fun pruneOrphans(context: Context, keepPaths: Collection<String>, olderThanMs: Long): Int =
        AvatarFileCleanup.pruneTemporary(cropDir(context), keepPaths, olderThanMs)

    fun uri(path: String): Uri = File(path).toUri()

    private fun saveDecodedToPath(
        context: Context,
        uri: Uri,
        targetPath: String,
        maxSide: Int,
    ): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSample(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return@runCatching null
        val scaled = scaleToMax(decoded, maxSide)
        val target = File(targetPath)
        target.parentFile?.mkdirs()
        try {
            AvatarFileTransfer.write(target) { staged ->
                FileOutputStream(staged).use { out ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "Avatar encoding failed" }
                }
            }
            target.absolutePath
        } finally {
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
        }
    }.getOrNull()

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

package com.miniichatNext.carter.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Base64OutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** 读取content:// 或file:// URI并返回base64+mime+名称+大小 */
data class LoadedAttachment(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val base64: String
)

/** 附件内容内联结果（文本文件才内联，二进制只给说明） */
sealed interface InlineAttachment {
    /** [truncated] = 命中内联上限，[text]只包含前[sizeBytes]字节 */
    data class Text(val text: String, val sizeBytes: Long, val truncated: Boolean = false) : InlineAttachment
    data class Skipped(val reason: String, val sizeBytes: Long) : InlineAttachment
    object Failed : InlineAttachment
}

class AttachmentTooLargeException(val limitBytes: Long) :
    RuntimeException("Attachment exceeds ${limitBytes / 1024 / 1024} MB limit")

object AttachmentLoader {
    /** 硬上限，将Base64+JSON编码的有效负载保持在请求限制范围内并避免OOM */
    const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024 * 1024  // 10 MB

    /** 单文件内联进上下文的上限，避免把上下文塞爆 */
    const val MAX_INLINE_TEXT_BYTES: Long = 512L * 1024  // 512 KB

    // 图片编码参数
    private const val IMG_MAX_DIMENSION = 10_000
    private const val IMG_MAX_PIXELS = 16_000_000L
    private const val IMG_JPEG_QUALITY = 85

    private val SUPPORTED_IMAGE_MIMES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "image/heic", "image/heif", "image/avif",
    )

    /** 粗略判断是否文本：含NUL或控制字符占比过高即视为二进制 */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.contains(0.toByte())) return false
        var ctrl = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c < 0x09 || c in 0x0E..0x1F) ctrl++
        }
        return ctrl * 100 / bytes.size < 2
    }

    /**
     * 按文本读取附件内容，供请求体内联使用
     * 超过[limitBytes]时保留已读部分并标记truncated（截断内联，而不是整份丢弃）
     * 只有判定为二进制才返回Skipped
     */
    fun readAsText(
        resolver: ContentResolver,
        uri: Uri,
        limitBytes: Long = MAX_INLINE_TEXT_BYTES
    ): InlineAttachment {
        val out = ByteArrayOutputStream()
        var truncated = false
        val opened = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (total + n > limitBytes) {
                        val keep = (limitBytes - total).toInt().coerceAtLeast(0)
                        if (keep > 0) out.write(buf, 0, keep)
                        truncated = true
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!opened) return InlineAttachment.Failed
        val bytes = out.toByteArray()
        if (!looksLikeText(bytes)) {
            return InlineAttachment.Skipped("二进制文件", bytes.size.toLong())
        }
        return InlineAttachment.Text(String(bytes, Charsets.UTF_8), bytes.size.toLong(), truncated)
    }

    fun queryNameSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    /**
     * 读取附件并base64编码。图片会在这里压缩（而不是导入时），压缩参数变了不用重导历史附件
     *
     * 图片走compressAndEncodeImage：inSampleSize采样+EXIF归一+JPEG重压+流式base64
     * 非图/GIF走原样流式base64
     */
    fun load(resolver: ContentResolver, uri: Uri, mimeFallback: String): LoadedAttachment {
        val (name, size) = queryNameSize(resolver, uri)
        if (size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(MAX_ATTACHMENT_BYTES)
        val mime = resolver.getType(uri) ?: guessMimeFromName(name) ?: mimeFallback

        // 图片压缩后再base64
        if (isCompressibleImage(mime, uri)) {
            val compressed = runCatching { compressAndEncodeImage(resolver, uri) }.getOrNull()
            if (compressed != null) {
                return LoadedAttachment(
                    name = name,
                    mimeType = compressed.mimeType,
                    sizeBytes = compressed.byteSize,
                    base64 = compressed.base64,
                )
            }
            // 压缩失败（解码失败等）→回落原样编码，不整张丢掉
        }

        val out = ByteArrayOutputStream()
        var total = 0L
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $uri" }
            Base64OutputStream(out, Base64.NO_WRAP).use { b64 ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(MAX_ATTACHMENT_BYTES)
                    b64.write(buf, 0, n)
                }
            }
        }
        return LoadedAttachment(
            name = name,
            mimeType = mime,
            sizeBytes = if (size > 0) size else total,
            base64 = out.toString(Charsets.ISO_8859_1.name()),
        )
    }

    private fun isCompressibleImage(mime: String, uri: Uri): Boolean {
        // GIF可能是动图，保持原样
        if (mime == "image/gif") return false
        if (mime in SUPPORTED_IMAGE_MIMES) return true
        if (mime.startsWith("image/")) return true
        // ContentResolver没给MIME时按后缀猜
        val guessed = guessMimeFromName(uri.lastPathSegment ?: "")
        return guessed != null && guessed != "image/gif" && guessed.startsWith("image/")
    }

    private data class EncodedImage(val base64: String, val mimeType: String, val byteSize: Long)

    /**
     * 解码→inSampleSize缩放→EXIF旋转归一→JPEG 85重压→流式base64
     * 参考rikkahub的File.compressAndEncode
     */
    private fun compressAndEncodeImage(resolver: ContentResolver, uri: Uri): EncodedImage? {
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateImageInSampleSize(
            bounds.outWidth, bounds.outHeight, IMG_MAX_DIMENSION, IMG_MAX_PIXELS
        )
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val normalized = normalizeByExif(bitmap, raw)

        return try {
            val bos = ByteArrayOutputStream()
            normalized.compress(Bitmap.CompressFormat.JPEG, IMG_JPEG_QUALITY, bos)
            val jpegBytes = bos.toByteArray()

            // 流式base64（避免再复制一份String）
            val out = ByteArrayOutputStream()
            Base64OutputStream(out, Base64.NO_WRAP).use { it.write(jpegBytes) }
            EncodedImage(
                base64 = out.toString(Charsets.ISO_8859_1.name()),
                mimeType = "image/jpeg",
                byteSize = jpegBytes.size.toLong(),
            )
        } finally {
            if (normalized !== bitmap) normalized.recycle()
            bitmap.recycle()
        }
    }

    private fun normalizeByExif(bitmap: Bitmap, raw: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(java.io.ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) return bitmap

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }

    /** 采样倍数：最长边≤[maxDim]，总像素≤[maxPixels]。纯函数，便于单测 */
    internal fun calculateImageInSampleSize(
        width: Int,
        height: Int,
        maxDim: Int,
        maxPixels: Long
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while ((height / sample) > maxDim ||
            (width / sample) > maxDim ||
            (width.toLong() / sample) * (height.toLong() / sample) > maxPixels
        ) sample *= 2
        return sample
    }

    fun formatBytes(b: Long): String {
        if (b <= 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            b < kb -> "${b}B"
            b < mb -> "%.1fKB".format(b / kb)
            b < gb -> "%.1fMB".format(b / mb)
            else -> "%.2fGB".format(b / gb)
        }
    }

    private fun guessMimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            else -> null
        }
    }
}
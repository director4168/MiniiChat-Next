package com.miniichatNext.carter.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.miniichatNext.carter.data.model.Attachment
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 把picker返回的content:// URI复制到app自有目录（filesDir/uploads/<uuid>.<ext>）
 * 同时计算内容SHA-256用于跨picker/跨路径去重
 */
object AttachmentImporter {
    private const val TAG = "AttachmentImporter"

    /** 上传目录名 */
    private const val UPLOAD_DIR = "uploads"

    private val IMAGE_MIMES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "image/heic", "image/heif", "image/avif",
    )

    data class ImportResult(
        val added: List<Attachment>,
        val skippedTooLarge: List<String>,
        val skippedFailed: List<String>,
        val dedupedAgainstExisting: List<String>,
    )

    fun uploadDir(context: Context): File {
        val dir = File(context.filesDir, UPLOAD_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 把一批picker URI全部导入
     *
     * @param existingHashes当前attachments里已有的SHA-256；命中即视为重复
     * @param maxFileBytes单个源文件硬上限
     */
    fun importAll(
        context: Context,
        uris: List<Uri>,
        existingHashes: Set<String>,
        maxFileBytes: Long = AttachmentLoader.MAX_ATTACHMENT_BYTES,
    ): ImportResult {
        val dir = uploadDir(context)
        val resolver = context.contentResolver
        val added = mutableListOf<Attachment>()
        val tooLarge = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val deduped = mutableListOf<String>()

        for (uri in uris) {
            try {
                val (name, declaredSize) = AttachmentLoader.queryNameSize(resolver, uri)
                if (declaredSize > maxFileBytes) {
                    tooLarge.add(name)
                    continue
                }
                val bytes = readBytesWithCap(resolver, uri, maxFileBytes)
                if (bytes == null) {
                    tooLarge.add(name)
                    continue
                }

                val mime = resolver.getType(uri)
                    ?: guessMimeByName(name)
                    ?: "application/octet-stream"
                val isImage = mime in IMAGE_MIMES || mime.startsWith("image/")

                val sha = sha256(bytes)
                if (sha in existingHashes || added.any { it.sha256 == sha }) {
                    deduped.add(name)
                    continue
                }

                val ext = extForMime(mime) ?: name.substringAfterLast('.', "").ifBlank { "bin" }
                val stored = File(dir, "${UUID.randomUUID()}.$ext")
                stored.writeBytes(bytes)

                added += Attachment(
                    type = if (isImage) "image" else "file",
                    uri = Uri.fromFile(stored).toString(),
                    mimeType = mime,
                    name = name,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha,
                )
            } catch (e: Exception) {
                Log.w(TAG, "import failed for $uri", e)
                failed.add(uri.toString())
            }
        }
        return ImportResult(added, tooLarge, failed, deduped)
    }

    /** 流式读取URI到ByteArray，超过[maxBytes]返回null */
    private fun readBytesWithCap(resolver: ContentResolver, uri: Uri, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
        } ?: return null
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun guessMimeByName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            else -> null
        }
    }

    private fun extForMime(mime: String): String? = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        "application/json" -> "json"
        "application/xml" -> "xml"
        "text/csv" -> "csv"
        else -> null
    }
}
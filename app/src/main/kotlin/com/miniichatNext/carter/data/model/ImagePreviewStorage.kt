/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import java.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal object ImagePreviewStorage {
    private const val MAX_BYTES = 20 * 1024 * 1024

    fun load(filesDir: File, source: String): File {
        val root = File(filesDir, "generated-images").canonicalFile
        check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdirs())
        val (mime, bytes) = when {
            source.startsWith("data:image/", true) -> decodeDataUri(source)
            source.startsWith("file:", true) -> {
                val file = File(URI(source)).canonicalFile
                require(file.path.startsWith(root.path + File.separator) && file.isFile)
                val ext = file.extension.lowercase(Locale.ROOT)
                (mimeFromExtension(ext) to file.readBytes())
            }
            source.startsWith("http://", true) || source.startsWith("https://", true) -> download(root, source)
            else -> error("Unsupported image address")
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val ext = extensionFor(mime)
        val target = File(root, "preview_${sha256(source)}.$ext")
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            val temp = File(root, target.name + ".tmp")
            try {
                temp.outputStream().use { it.write(bytes) }
                check(temp.renameTo(target) || (target.isFile && target.length() == bytes.size.toLong()))
            } finally { temp.delete() }
        }
        return target
    }

    fun html(file: File): String {
        val mime = mimeFromExtension(file.extension.lowercase(Locale.ROOT))
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}img{display:block;width:100%;height:100%;object-fit:contain}</style><img alt='image' src='data:$mime;base64,$encoded'>"
    }

    fun extension(file: File): String = file.extension.ifBlank { "img" }

    private fun decodeDataUri(source: String): Pair<String, ByteArray> {
        val comma = source.indexOf(',')
        require(comma > 0)
        val header = source.substring(5, comma)
        val mime = header.substringBefore(';').lowercase(Locale.ROOT)
        require(mime.startsWith("image/") && "base64" in header.lowercase(Locale.ROOT))
        val payload = source.substring(comma + 1)
        require(payload.length <= (MAX_BYTES * 4 / 3) + 16)
        return mime to Base64.getDecoder().decode(payload)
    }

    private fun download(root: File, source: String): Pair<String, ByteArray> {
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif,image/svg+xml")
        return try {
            require(connection.responseCode in 200..299) { "Image server returned HTTP ${connection.responseCode}" }
            val mime = connection.contentType?.substringBefore(';')?.lowercase(Locale.ROOT)
                ?.takeIf { it.startsWith("image/") } ?: mimeFromExtension(source.substringBefore('?').substringAfterLast('.'))
            require(mime.startsWith("image/"))
            val length = connection.contentLengthLong
            require(length <= MAX_BYTES) { "Image is too large" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_BYTES) { "Image is too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            mime to bytes
        } finally { connection.disconnect() }
    }

    private fun mimeFromExtension(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }

    private fun extensionFor(mime: String): String = when (mime.substringAfter('/')) {
        "jpeg", "jpg" -> "jpg"
        "svg+xml" -> "svg"
        "webp" -> "webp"
        "gif" -> "gif"
        "bmp" -> "bmp"
        "png" -> "png"
        else -> error("Unsupported image type: $mime")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

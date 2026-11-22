/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import com.miniichatNext.carter.util.copyBounded
import com.miniichatNext.carter.util.writeAtomicFile
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal object ImagePreviewStorage {
    const val DOCUMENT_URL = "https://miniichat.local/image/index.html"
    const val IMAGE_URL = "https://miniichat.local/image/content"
    const val HTML = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
        "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none';img-src https://miniichat.local;style-src 'unsafe-inline'\">" +
        "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden}img{display:block;width:100%;height:100%;object-fit:contain}</style>" +
        "<img alt='image' src='$IMAGE_URL'>"
    private const val MAX_BYTES = GeneratedImageStore.MAX_BYTES

    fun load(filesDir: File, source: String): File {
        if (source.startsWith(GeneratedImageStore.PUBLIC_IMAGE_PREFIX, true)) {
            val name = source.substring(GeneratedImageStore.PUBLIC_IMAGE_PREFIX.length)
                .substringBefore('?').substringBefore('#')
            return requireNotNull(GeneratedImageStore.findLocalByName(filesDir, name)) { "Image is not available" }
        }
        if (source.startsWith(GeneratedImageStore.LOCAL_IMAGE_PREFIX, true)) {
            val encoded = source.substring(GeneratedImageStore.LOCAL_IMAGE_PREFIX.length)
                .substringBefore('?').substringBefore('#')
            val path = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            val file = File(path)
            require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "Image is not available" }
            return file
        }
        if (source.startsWith("file:", true)) return requireNotNull(GeneratedImageStore.findLocal(filesDir, source)) { "Image is not available" }
        val root = GeneratedImageStore.directory(filesDir)
        val key = "preview_" + MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return if (source.startsWith("data:image/", true)) {
            val comma = source.indexOf(',')
            require(comma in 6..127 && source.substring(5, comma).endsWith(";base64", true) && source.length - comma <= MAX_BYTES * 4 / 3 + 16)
            val mime = source.substring(5, comma).substringBefore(';').lowercase(Locale.ROOT)
            Base64.getDecoder().wrap(source.substring(comma + 1).byteInputStream(Charsets.US_ASCII)).use { save(root, key, mime, it) }
        } else download(root, key, source)
    }

    /**
     * 远程图片先校验目标地址，再按真实字节上限写入。生成图落盘和预览缓存共用这一入口，
     * 避免模型输出把设备引向localhost、局域网或云元数据地址。
     */
    internal fun downloadTo(root: File, key: String, source: String, overwrite: Boolean = true): File {
        val uri = URI(source)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            uri.host != null && uri.rawUserInfo == null) { "Unsupported image address" }
        require(isPublicHost(uri.host)) { "Image host is not public" }
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif,image/svg+xml,image/bmp")
        }
        return try {
            require(connection.responseCode == 200) { "Image server returned HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= MAX_BYTES) { "Image is too large" }
            val declared = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.startsWith("image/") }
            val extension = uri.path.orEmpty().substringAfterLast('.', "")
            val mime = declared ?: knownMimeFromExtension(extension) ?: "image/png"
            connection.inputStream.use { save(root, key, mime, it, overwrite) }
        } finally { connection.disconnect() }
    }

    private fun download(root: File, key: String, source: String): File =
        downloadTo(root, key, source)

    private fun save(
        root: File,
        key: String,
        mime: String,
        input: InputStream,
        overwrite: Boolean = true,
    ): File {
        val file = File(root, "$key.${extensionFor(mime)}")
        writeAtomicFile(file, overwrite = overwrite) { staged ->
            val size = staged.outputStream().use { input.copyBounded(it, MAX_BYTES.toLong()) }
            require(size > 0) { "Empty image" }
        }
        return file
    }

    fun open(file: File): InputStream {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "Image is not available" }
        return file.inputStream()
    }

    fun extension(file: File): String =
        extensionForOrNull(mime(file)) ?: file.extension.ifBlank { "img" }

    fun mime(file: File): String = sniffMime(file) ?: mimeFromExtension(file.extension)

    private fun isPublicHost(host: String): Boolean {
        val addresses = InetAddress.getAllByName(host)
        return addresses.isNotEmpty() && addresses.all(::isPublicAddress)
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 100 && second in 64..127) return false
        }
        return true
    }

    private fun sniffMime(file: File): String? {
        if (!file.isFile || file.length() == 0L) return null
        val head = ByteArray(16)
        val count = file.inputStream().use { it.read(head) }
        if (count >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4e.toByte() && head[3] == 0x47.toByte() &&
            head[4] == 0x0d.toByte() && head[5] == 0x0a.toByte() &&
            head[6] == 0x1a.toByte() && head[7] == 0x0a.toByte()) return "image/png"
        if (count >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()) return "image/jpeg"
        if (count >= 6 && String(head, 0, 6, Charsets.US_ASCII).startsWith("GIF8")) return "image/gif"
        if (count >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
        if (count >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) return "image/bmp"
        if (file.length() <= 2 * 1024 * 1024) {
            val svg = ByteArray(512)
            val svgCount = file.inputStream().use { it.read(svg) }
            val text = String(svg, 0, svgCount, Charsets.UTF_8).trimStart()
            if (text.startsWith("<svg", true) ||
                (text.startsWith("<?xml", true) && text.contains("<svg", true))) return "image/svg+xml"
        }
        return null
    }

    private fun mimeFromExtension(extension: String): String =
        knownMimeFromExtension(extension) ?: "image/png"

    private fun knownMimeFromExtension(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> null
    }

    private fun extensionFor(mime: String): String =
        requireNotNull(extensionForOrNull(mime)) { "Unsupported image type: $mime" }

    private fun extensionForOrNull(mime: String): String? = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/svg+xml" -> "svg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/png" -> "png"
        else -> null
    }
}

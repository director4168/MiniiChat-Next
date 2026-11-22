/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import com.miniichatNext.carter.util.InputLimitExceeded
import com.miniichatNext.carter.util.copyBounded
import com.miniichatNext.carter.util.publishFile
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal data class MaterializedImages(val content: String, val attachments: List<Attachment>, val replacements: Map<String, String> = emptyMap())

internal object GeneratedImageStore {
    internal const val MAX_BYTES = 20 * 1024 * 1024
    internal const val DIRECTORY = "generated-images"
    private const val MAX_ENCODED = 28 * 1024 * 1024
    private const val MAX_IMAGES = 16
    private val dataPattern = Regex(
        """!\[([^\]\r\n]*)]\(data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\r\n]+)\)""",
        RegexOption.IGNORE_CASE
    )
    private val remotePattern = Regex(
        """!\[([^\]\r\n]*)]\((https?://[^)\s]+)\)""",
        RegexOption.IGNORE_CASE
    )
    private val localReference = Regex("""(!\[[^\]\r\n]*]\((?:file:|backup://generated/)[^()\s]+\))""")
    internal const val PUBLIC_IMAGE_PREFIX = "https://miniichat.local/generated/"
    internal const val LOCAL_IMAGE_PREFIX = "https://miniichat.local/local-image/"
    private val localFileImage = Regex(
        """(!\[([^\]\r\n]*)]\((file:[^)\s]+)\))""",
        RegexOption.IGNORE_CASE
    )

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY).also {
        check(!Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.mkdirs())) { "Invalid image directory" }
    }

    fun publicImageUrl(file: File): String = PUBLIC_IMAGE_PREFIX + file.name

    fun findLocalByName(filesDir: File, name: String): File? = runCatching {
        val root = directory(filesDir).canonicalFile
        val file = File(root, name).canonicalFile
        file.takeIf { it.parentFile == root && it.isFile && it.length() in 1..MAX_BYTES.toLong() }
    }.getOrNull()

    /** Markdown解析器的安全链接规则会拦掉file:；渲染前把本地生成图映射成内部https地址。 */
    fun rewriteLocalMarkdownImages(text: String): String {
        val codeRanges = markdownCodeRanges(text)
        return localFileImage.replace(text) { match ->
            if (isInsideCode(match.range.first, codeRanges)) return@replace match.value
            val path = localPath(match.groupValues[3]) ?: return@replace match.value
            val target = if (path.contains("/generated-images/")) {
                PUBLIC_IMAGE_PREFIX + path.substringAfterLast('/')
            } else {
                LOCAL_IMAGE_PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(path.toByteArray(Charsets.UTF_8))
            }
            "![${match.groupValues[2]}]($target)"
        }
    }

    fun findLocal(filesDir: File, reference: String): File? = runCatching {
        val root = File(filesDir, DIRECTORY)
        if (Files.isSymbolicLink(root.toPath())) return null
        if (reference.startsWith(PUBLIC_IMAGE_PREFIX, true)) {
            val name = reference.substring(PUBLIC_IMAGE_PREFIX.length).substringBefore('?').substringBefore('#')
            return findLocalByName(filesDir, name)
        }
        val path = localPath(reference) ?: return null
        val source = File(path)
        if (Files.isSymbolicLink(source.toPath())) return null
        val file = source.canonicalFile
        val imageRoot = root.canonicalFile
        file.takeIf { it.path.startsWith(imageRoot.path + File.separator) && it.isFile && it.length() in 1..MAX_BYTES.toLong() }
    }.getOrNull()

    /** 兼容file:/path、file:///path与带authority的file URI，避免预览层因URI形态差异误判。 */
    private fun localPath(reference: String): String? {
        if (!reference.startsWith("file:", true)) return null
        val parsed = runCatching { URI(reference) }.getOrNull()
        if (parsed?.scheme.equals("file", true)) {
            val path = parsed?.path
            if (!path.isNullOrBlank()) {
                val host = parsed.host
                return if (!host.isNullOrBlank() && host != "localhost") "/$host$path" else path
            }
        }
        val raw = reference.substringAfter(':', "").removePrefix("//").substringBefore('?').substringBefore('#')
        if (raw.isBlank()) return null
        return if (raw.startsWith("/")) raw else "/$raw"
    }

    fun references(text: String): List<String> =
        localReference.findAll(text).map { match -> referenceOf(match.groupValues[1]) }.toList()

    fun mapReferences(text: String, transform: (String) -> String): String = localReference.replace(text) { match ->
        val whole = match.groupValues[1]
        val reference = referenceOf(whole)
        val open = whole.indexOf("](") + 2
        val close = whole.lastIndexOf(')')
        whole.substring(0, open) + transform(reference) + whole.substring(close)
    }

    private fun referenceOf(markdown: String): String =
        markdown.substringAfter("](", "").substringBeforeLast(")", "")

    fun deleteLocal(filesDir: File, reference: String): Boolean = runCatching {
        findLocal(filesDir, reference)?.delete() == true
    }.getOrDefault(false)

    fun materialize(content: String, filesDir: File): MaterializedImages {
        val attachments = mutableListOf<Attachment>()
        val replacements = mutableMapOf<String, String>()
        val published = mutableListOf<File>()
        try {
            val root = directory(filesDir)
            var rewritten = replaceDataImages(content, root, attachments, replacements, published)
            rewritten = replaceRemoteImages(rewritten, root, attachments, replacements, published)
            return MaterializedImages(rewritten, attachments, replacements)
        } catch (error: Exception) {
            published.forEach { it.delete() }
            throw error
        }
    }

    private fun replaceDataImages(
        content: String,
        root: File,
        attachments: MutableList<Attachment>,
        replacements: MutableMap<String, String>,
        published: MutableList<File>,
    ): String {
        val codeRanges = markdownCodeRanges(content)
        return dataPattern.replace(content) { match ->
            if (isInsideCode(match.range.first, codeRanges)) return@replace match.value
            replacements[match.value]?.let { return@replace it }
            if (attachments.size >= MAX_IMAGES) return@replace match.value
            val encoded = match.groupValues[3]
            if (encoded.length > MAX_ENCODED) return@replace match.value
            val declared = extensionForSubtype(match.groupValues[2]) ?: return@replace match.value
            val target = writeDecodedImage(root, encoded, declared) ?: return@replace match.value
            published += target
            attachments += attachment(target)
            "![${match.groupValues[1]}](${target.toURI().toASCIIString()})"
                .also { replacements[match.value] = it }
        }
    }

    private fun replaceRemoteImages(
        content: String,
        root: File,
        attachments: MutableList<Attachment>,
        replacements: MutableMap<String, String>,
        published: MutableList<File>,
    ): String {
        val codeRanges = markdownCodeRanges(content)
        return remotePattern.replace(content) { match ->
            if (isInsideCode(match.range.first, codeRanges)) return@replace match.value
            replacements[match.value]?.let { return@replace it }
            if (attachments.size >= MAX_IMAGES) return@replace match.value
            val source = match.groupValues[2]
            val target = runCatching {
                ImagePreviewStorage.downloadTo(root, UUID.randomUUID().toString(), source, overwrite = false)
            }.getOrNull() ?: return@replace match.value
            published += target
            attachments += attachment(target)
            "![${match.groupValues[1]}](${target.toURI().toASCIIString()})"
                .also { replacements[match.value] = it }
        }
    }

    private fun writeDecodedImage(root: File, encoded: String, fallbackExtension: String): File? {
        val staged = Files.createTempFile(root.toPath(), ".pending-", ".img").toFile()
        return try {
            val size = staged.outputStream().use { output ->
                Base64.getMimeDecoder().wrap(encoded.byteInputStream(Charsets.US_ASCII)).use { input ->
                    input.copyBounded(output, MAX_BYTES.toLong())
                }
            }
            require(size > 0) { "Empty image" }
            val extension = detectImageExtension(staged) ?: fallbackExtension
            if (extension == "svg" && size > 2 * 1024 * 1024) {
                staged.delete()
                return null
            }
            val target = File(root, "${UUID.randomUUID()}.$extension")
            publishFile(staged, target, overwrite = false)
            target
        } catch (_: InputLimitExceeded) {
            staged.delete()
            null
        } catch (_: IllegalArgumentException) {
            staged.delete()
            null
        } catch (failure: IOException) {
            staged.delete()
            if (failure.message?.startsWith("Illegal base64") == true || failure.message?.contains("ending unit") == true) null
            else throw failure
        } catch (failure: Exception) {
            staged.delete()
            throw failure
        }
    }

    private fun attachment(file: File): Attachment = Attachment(
        type = "image",
        uri = file.toURI().toASCIIString(),
        name = file.name,
        mimeType = ImagePreviewStorage.mime(file),
        sizeBytes = file.length(),
    )

    private fun extensionForSubtype(subtype: String): String? = when (subtype.lowercase(Locale.ROOT)) {
        "png" -> "png"
        "jpeg", "jpg" -> "jpg"
        "webp" -> "webp"
        "gif" -> "gif"
        "svg+xml", "svg" -> "svg"
        "bmp" -> "bmp"
        else -> null
    }

    private fun detectImageExtension(file: File): String? {
        if (!file.isFile || file.length() == 0L) return null
        val head = ByteArray(16)
        val count = file.inputStream().use { it.read(head) }
        if (count >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4e.toByte() && head[3] == 0x47.toByte() &&
            head[4] == 0x0d.toByte() && head[5] == 0x0a.toByte() &&
            head[6] == 0x1a.toByte() && head[7] == 0x0a.toByte()) return "png"
        if (count >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()) return "jpg"
        if (count >= 6 && String(head, 0, 6, Charsets.US_ASCII).startsWith("GIF8")) return "gif"
        if (count >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP") return "webp"
        if (count >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) return "bmp"
        if (file.length() <= 2 * 1024 * 1024) {
            val svg = ByteArray(512)
            val svgCount = file.inputStream().use { it.read(svg) }
            val text = String(svg, 0, svgCount, Charsets.UTF_8).trimStart()
            if (text.startsWith("<svg", true) ||
                (text.startsWith("<?xml", true) && text.contains("<svg", true))) return "svg"
        }
        return null
    }

    private fun isInsideCode(offset: Int, ranges: List<IntRange>): Boolean =
        ranges.any { offset in it }

    /**
     * Markdown代码围栏和四空格代码块里的同形文本不参与图片提取，
     * 避免把示例代码里的data URI改写成本地文件。
     */
    private fun markdownCodeRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var offset = 0
        var fence: String? = null
        var blockStart = 0
        for (line in text.split('\n')) {
            val start = offset
            val end = start + line.length
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (fence == null && marker != null) {
                fence = marker
                blockStart = start
            } else if (fence != null && marker == fence) {
                ranges += blockStart..end
                fence = null
            } else if (fence == null && (line.startsWith("    ") || line.startsWith("\t"))) {
                ranges += start..end
            }
            offset = end + 1
        }
        if (fence != null) ranges += blockStart..text.lastIndex.coerceAtLeast(0)
        return ranges
    }
}

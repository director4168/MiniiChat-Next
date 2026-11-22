/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.skills

import com.miniichatNext.carter.util.copyBounded
import com.miniichatNext.carter.util.readBounded
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipInputStream

internal object SkillImport {
    const val METADATA_BYTES = 512 * 1024
    private const val SOURCE_BYTES = 16L * 1024 * 1024
    private const val TOTAL_BYTES = 32L * 1024 * 1024
    private const val ENTRY_BYTES = 8L * 1024 * 1024
    private const val ENTRY_COUNT = 512

    fun prepare(stage: File, sourceName: String, open: () -> InputStream): Pair<SkillFrontmatterParser.Parsed, File> {
        val source = File(stage, "source")
        open().use { input -> source.outputStream().use { input.copyBounded(it, SOURCE_BYTES) } }
        val signature = source.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        val isZip = signature.contentEquals(byteArrayOf(0x50, 0x4b, 3, 4)) || signature.contentEquals(byteArrayOf(0x50, 0x4b, 5, 6))
        val root = File(stage, "content").apply { check(mkdir()) }
        val meta = if (isZip) extract(source, root) else File(root, "SKILL.md").also { target ->
            require(source.length() <= METADATA_BYTES) { "SKILL.md is too large" }
            check(source.renameTo(target)) { "Could not stage SKILL.md" }
        }
        val result = parse(meta, sourceName)
        if (meta.name != "SKILL.md") {
            val canonicalName = File(meta.parentFile, "SKILL.md")
            check(meta.renameTo(canonicalName)) { "Could not normalize SKILL.md name" }
        }
        return result to requireNotNull(meta.parentFile)
    }

    private fun extract(source: File, root: File): File {
        val seen = hashSetOf<String>()
        var metadata: File? = null
        var total = 0L
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.trimEnd('/')
                require(name.isNotBlank() && name.length <= 1024 && '\\' !in name && ':' !in name && '\u0000' !in name &&
                    name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid skill archive path" }
                require(seen.add(name.lowercase(Locale.ROOT)) && seen.size <= ENTRY_COUNT) { "Duplicate or too many skill entries" }
                val file = File(root, name)
                val isMetadata = !entry.isDirectory && file.name.equals("SKILL.md", true)
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(file.toPath())
                        require(zip.read() < 0) { "Unexpected data in skill directory entry" }
                    }
                    else -> {
                        require(!file.exists()) { "Conflicting skill archive path" }
                        Files.createDirectories(requireNotNull(file.parentFile).toPath())
                        val limit = if (isMetadata) METADATA_BYTES.toLong() else ENTRY_BYTES
                        total += file.outputStream().use { zip.copyBounded(it, minOf(limit, TOTAL_BYTES - total)) }
                    }
                }
                if (isMetadata) {
                    require(metadata == null) { "Skill archive contains more than one SKILL.md" }
                    metadata = file
                }
                zip.closeEntry()
            }
        }
        return requireNotNull(metadata) { "Skill archive is missing SKILL.md" }
    }

    fun parse(meta: File, fallback: String): SkillFrontmatterParser.Parsed {
        val bytes = meta.inputStream().use { it.readBounded(METADATA_BYTES) }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val front = SkillFrontmatterParser.split(text).first
        require(front.lineSequence().any { line -> line.substringBefore(':').trim() == "name" && line.substringAfter(':', "").isNotBlank() }) {
            "SKILL.md is missing a name"
        }
        val parsed = requireNotNull(SkillFrontmatterParser.parse(text, fallback)) { "Empty SKILL.md" }
        require(parsed.description.isNotBlank()) { "SKILL.md is missing a description" }
        return parsed
    }

    fun download(url: String, fetch: (InputStream) -> Skill): Skill {
        val address = URI(url.trim())
        require(address.scheme in setOf("http", "https") && address.host != null && address.rawUserInfo == null) { "Invalid skill URL" }
        val connection = (address.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 10000
            instanceFollowRedirects = true
        }
        return try {
            require(connection.responseCode == 200 && connection.contentLengthLong <= SOURCE_BYTES) { "Skill download failed" }
            connection.inputStream.use(fetch)
        } finally { connection.disconnect() }
    }
}

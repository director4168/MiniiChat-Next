/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.util.copyBounded
import com.miniichatNext.carter.util.deleteTreeNoFollow
import com.miniichatNext.carter.util.publishFile
import com.miniichatNext.carter.util.readBounded
import com.miniichatNext.carter.util.writeAtomicFile
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class WorkspaceFileSystem(private val config: WorkspaceConfig = WorkspaceConfig()) {
    init {
        require(config.maxReadBytes in 1..Int.MAX_VALUE.toLong() && config.maxWriteBytes > 0)
        require(config.maxListEntries > 0 && config.maxSearchResults > 0)
    }

    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.isDirectory) { "Not a directory: $path" }
        return Files.newDirectoryStream(dir.toPath()).use { entries ->
            entries.asSequence().filter(::visible).take(config.maxListEntries).map { it.toFile().toEntry(root) }.toList()
        }.sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readBytes(root: File, path: String, maxBytes: Int): ByteArray = readFile(resolvePath(root, path), maxBytes)

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String =
        readBytes(root, path, config.maxReadBytes.toInt()).toString(charset)

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = write(root, path, text.toByteArray(charset), overwrite)

    fun editText(root: File, path: String, old: String, new: String, replaceAll: Boolean): Pair<WorkspaceFileEntry, ReplaceTextResult> {
        val file = resolvePath(root, path)
        val bytes = readFile(file, config.maxReadBytes.toInt())
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val edit = replaceText(text, old, new, replaceAll, listOf(ExactReplacer, LineTrimmedReplacer))
        return write(root, path, edit.updated.toByteArray(Charsets.UTF_8), overwrite = true, expected = bytes) to edit
    }

    fun replaceTextIfUnchanged(root: File, path: String, expected: String, text: String): WorkspaceFileEntry =
        write(root, path, text.toByteArray(Charsets.UTF_8), overwrite = true, expected = expected.toByteArray(Charsets.UTF_8))

    private fun write(root: File, path: String, bytes: ByteArray, overwrite: Boolean, expected: ByteArray? = null): WorkspaceFileEntry {
        require(bytes.size <= config.maxWriteBytes) { "Content is too large to write" }
        val file = resolvePath(root, path)
        require(file != root.canonicalFile && (!file.exists() || file.isFile)) { "Not a file: $path" }
        require(overwrite || !file.exists()) { "File already exists: $path" }
        file.parentFile?.mkdirs()
        writeAtomicFile(file, overwrite, beforeCommit = {
            require(resolvePath(root, path) == file) { "File path changed during edit" }
            if (expected != null) require(readFile(file, config.maxReadBytes.toInt()).contentEquals(expected)) { "File changed during edit" }
        }) { it.writeBytes(bytes) }
        return file.toEntry(root)
    }

    private fun readFile(file: File, limit: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Not a regular file: ${file.name}" }
        require(file.length() <= limit) { "File is too large to read" }
        return Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { it.readBounded(limit) }
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry = inputStream.use { input ->
        val requested = resolvePath(root, path)
        require(requested != root.canonicalFile) { "Not a file: $path" }
        requested.parentFile?.mkdirs()
        val file = if (requested.exists()) resolveConflict(requested) else requested
        writeAtomicFile(file, overwrite = false) { staged ->
            staged.outputStream().use { input.copyBounded(it, MAX_IMPORT_BYTES) }
        }
        file.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        return (1..1000).asSequence().map { File(file.parentFile, "${file.nameWithoutExtension} ($it)$ext") }
            .firstOrNull { !Files.exists(it.toPath(), NOFOLLOW_LINKS) } ?: error("No available filename")
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        val file = resolvePath(root, path, allowFinalLink = true)
        require(file != root.canonicalFile) { "Refusing to delete workspace root" }
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return false
        return if (recursive) deleteTreeNoFollow(file) else Files.deleteIfExists(file.toPath())
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        val from = resolvePath(root, source)
        val to = resolvePath(root, target)
        require(from != root.canonicalFile && to != root.canonicalFile) { "Refusing to move workspace root" }
        require(Files.isDirectory(from.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(from.toPath(), NOFOLLOW_LINKS)) { "Source is not a regular entry: $source" }
        if (from == to) return from.toEntry(root)
        require(!to.toPath().startsWith(from.toPath())) { "Destination is inside source" }
        require(!to.exists() || (overwrite && from.isFile && to.isFile)) { "Target already exists: $target" }
        to.parentFile?.mkdirs()
        publishFile(from, to, overwrite && from.isFile)
        return to.toEntry(root)
    }

    fun copy(root: File, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry {
        val from = resolvePath(root, source)
        val to = resolvePath(root, target)
        require(Files.isRegularFile(from.toPath(), NOFOLLOW_LINKS) && from != to) { "Not a separate regular source file" }
        require(to != root.canonicalFile && (!to.exists() || (overwrite && to.isFile))) { "Destination is not writable: $target" }
        to.parentFile?.mkdirs()
        writeAtomicFile(to, overwrite) { staged ->
            Files.newInputStream(from.toPath(), NOFOLLOW_LINKS).use { input ->
                staged.outputStream().use { input.copyBounded(it, MAX_IMPORT_BYTES) }
            }
        }
        return to.toEntry(root)
    }

    fun mkdir(root: File, path: String): WorkspaceFileEntry {
        val dir = resolvePath(root, path)
        Files.createDirectories(dir.toPath())
        return dir.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank() && pattern.length <= 512) { "Invalid glob" }
        val start = resolvePath(root, path).toPath()
        require(Files.isDirectory(start, NOFOLLOW_LINKS)) { "Not a directory: $path" }
        val matchers = (listOf(pattern) + listOfNotNull(pattern.takeIf { it.startsWith("**/") }?.removePrefix("**/")))
            .map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        return walk(start) { paths ->
            paths.filter { it != start && visible(it) && matchers.any { matcher -> matcher.matches(start.relativize(it)) } }
                .take(config.maxListEntries).map { it.toFile().toEntry(root) }.toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        maxResults: Int = config.maxSearchResults,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank() && query.length <= 512) { "Invalid search pattern" }
        val start = resolvePath(root, path).toPath()
        require(Files.isDirectory(start, NOFOLLOW_LINKS)) { "Not a directory: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = Regex(if (regex) query else Regex.escape(query), options)
        val include = includeGlob?.takeIf { it.isNotBlank() }?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val results = mutableListOf<WorkspaceSearchMatch>()
        val limit = maxResults.coerceIn(1, config.maxSearchResults)
        walk(start) { paths ->
            for (entry in paths) {
                if (results.size >= limit) break
                if (!visible(entry) || !Files.isRegularFile(entry, NOFOLLOW_LINKS) || include?.matches(start.relativize(entry)) == false) continue
                val file = entry.toFile()
                if (file.length() > config.maxReadBytes) continue
                val text = try { readFile(file, config.maxReadBytes.toInt()).toString(Charsets.UTF_8) } catch (_: java.io.IOException) { continue }
                if ('\u0000' in text) continue
                for ((index, line) in text.lineSequence().withIndex()) {
                    if (results.size >= limit) break
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (matcher.containsMatchIn(line)) results += WorkspaceSearchMatch(file.relativeTo(root.canonicalFile).invariantSeparatorsPath, index + 1, line.take(200))
                }
            }
        }
        return results
    }

    private fun visible(path: Path): Boolean = !path.fileName.toString().startsWith(".l2s.") &&
        (Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS))

    private fun <T> walk(start: Path, block: (Sequence<Path>) -> T): T = Files.walk(start, MAX_SEARCH_DEPTH).use { stream ->
        block(stream.iterator().asSequence().take(MAX_SEARCH_ENTRIES).onEach {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        })
    }

    private fun resolvePath(root: File, path: String, allowFinalLink: Boolean = false): File {
        require(!Files.isSymbolicLink(root.toPath())) { "Workspace root is a symbolic link" }
        val normalized = path.replace('\\', '/').trim().trimStart('/')
        require('\u0000' !in normalized) { "Invalid character in path" }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        require(".." !in segments) { "Parent directory segments are not allowed" }
        val base = root.canonicalFile
        var cursor = base
        segments.forEachIndexed { index, part ->
            cursor = File(cursor, part)
            require((allowFinalLink && index == segments.lastIndex) || !Files.isSymbolicLink(cursor.toPath())) {
                "Symbolic links are not followed by workspace file operations"
            }
        }
        return cursor
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativeTo(root.canonicalFile).invariantSeparatorsPath,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private companion object {
        const val MAX_IMPORT_BYTES = 128L * 1024 * 1024
        const val MAX_SEARCH_DEPTH = 64
        const val MAX_SEARCH_ENTRIES = 30_000
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.util.deleteTreeNoFollow
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext


class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    suspend fun install(
        root: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
        expectedAbi: String? = null,
        onBeforeCommit: suspend () -> Unit = {},
    ) {
        val address = URI(url)
        require(address.scheme in setOf("https", "http") && address.host != null && address.userInfo == null) { "Invalid Rootfs download URL" }
        val expectedDigest = address.rawFragment?.let { fragment ->
            require(Regex("sha256=[0-9A-Fa-f]{64}").matches(fragment)) { "Rootfs checksum must be #sha256 followed by 64 hexadecimal digits" }
            fragment.substringAfter('=')
        }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromUrl(url)
        val tempDir = manager.tempDir(root)
        val work = Files.createTempDirectory(tempDir.toPath(), "rootfs-install-").toFile()
        val archive = File(work, "rootfs.${format.extension}")
        val stagingDir = File(work, "staging")
        val backupDir = File(tempDir, "rootfs-backup")
        val linuxDir = manager.linuxDir(root)
        try {
            runInterruptible(Dispatchers.IO) {
                check(stagingDir.mkdir()) { "Could not stage Rootfs" }
                download(url.substringBefore('#'), archive, expectedDigest, onProgress)
                extractTar(archive, stagingDir, format, onProgress)
                require(RootfsLayout.isUsable(stagingDir)) { "Rootfs is missing an executable shell or env" }
                if (expectedAbi != null) {
                    require(RootfsLayout.matchesAbi(stagingDir, expectedAbi)) { "Rootfs architecture does not match $expectedAbi" }
                }
            }
            onBeforeCommit()
            runInterruptible(Dispatchers.IO) {
                recoverRootfsBackup(linuxDir, backupDir)
                commitStagedRootfs(stagingDir, linuxDir, backupDir) { installed ->
                    patcher.patch(installed)
                    require(RootfsLayout.isUsable(installed)) { "Installed Rootfs is missing an executable shell or env" }
                }
                onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { deleteTreeNoFollow(work) }
            }
        }
    }

    private fun download(
        url: String,
        target: File,
        expectedDigest: String?,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            require(code == 200) { "Rootfs download failed: HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            val maxBytes = minOf(MAX_ARCHIVE_BYTES, (requireNotNull(target.parentFile).usableSpace - DISK_RESERVE_BYTES).coerceAtLeast(0L))
            require(maxBytes > 0 && (totalBytes == null || totalBytes <= maxBytes)) { "Insufficient space or Rootfs download too large" }
            val digest = expectedDigest?.let { MessageDigest.getInstance("SHA-256") }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytesRead += read
                        require(bytesRead <= maxBytes) { "Rootfs download exceeds storage budget" }
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    require(bytesRead > 0 && (totalBytes == null || bytesRead == totalBytes)) { "Incomplete Rootfs download" }
                    if (digest != null) {
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        require(actual.equals(expectedDigest, true)) { "Rootfs SHA-256 mismatch" }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        require(targetDir.isDirectory && !Files.isSymbolicLink(targetDir.toPath())) { "Invalid Rootfs staging directory" }
        BufferedInputStream(archive.inputStream()).use { archiveStream ->
            format.wrapStream(archiveStream).use { input ->
                var entries = 0
                var headers = 0
                var extractedBytes = 0L
                val maxBytes = minOf(MAX_EXTRACTED_BYTES, (targetDir.usableSpace - DISK_RESERVE_BYTES).coerceAtLeast(0L))
                val hardLinks = mutableListOf<Pair<File, String>>()
                val usedPaths = hashSetOf<File>()
                var pendingName: String? = null
                var pendingLinkName: String? = null
                while (true) {
                    checkInterrupted()
                    val rawHeader = input.readTarHeader() ?: break
                    require(++headers <= MAX_ENTRIES && rawHeader.size in 0..maxBytes - extractedBytes) { "Rootfs archive exceeds extraction budget" }
                    extractedBytes += rawHeader.size
                    val header = rawHeader.copy(
                        name = pendingName ?: rawHeader.name,
                        linkName = pendingLinkName ?: rawHeader.linkName,
                    )
                    when (rawHeader.type) {
                        TarEntryType.LONG_NAME, TarEntryType.LONG_LINK, TarEntryType.PAX -> {
                            val metadata = input.readExactly(rawHeader.size)
                            when (rawHeader.type) {
                                TarEntryType.LONG_NAME -> pendingName = metadata.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                                TarEntryType.LONG_LINK -> pendingLinkName = metadata.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                                else -> parsePax(metadata).let { pax ->
                                    pendingName = pax["path"] ?: pendingName
                                    pendingLinkName = pax["linkpath"] ?: pendingLinkName
                                    require(pax["size"] == null && pax.keys.none { it.startsWith("GNU.sparse") }) { "Unsupported tar entry layout" }
                                }
                            }
                            input.skipFully(rawHeader.size.paddingSize())
                            continue
                        }
                        else -> Unit
                    }
                    pendingName = null
                    pendingLinkName = null
                    val target = targetDir.safeResolve(header.name)
                    if (target == targetDir.canonicalFile) {
                        require(header.type == TarEntryType.DIRECTORY && header.size == 0L) { "Invalid Rootfs base entry" }
                        continue
                    }
                    require(usedPaths.add(target) || (header.type == TarEntryType.DIRECTORY && target.isDirectory)) { "Duplicate Rootfs archive entry" }
                    target.parentFile?.mkdirs()
                    when (header.type) {
                        TarEntryType.DIRECTORY -> target.mkdirs()
                        TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                        TarEntryType.HARDLINK -> hardLinks.add(target to header.linkName)
                        TarEntryType.FILE -> {
                            target.outputStream().use { output -> input.copyExactly(output, header.size) }
                            target.applyMode(header.mode)
                        }
                        TarEntryType.LONG_NAME,
                        TarEntryType.LONG_LINK,
                        TarEntryType.PAX,
                        TarEntryType.OTHER -> Unit
                    }
                    if (header.type != TarEntryType.FILE) input.skipFully(header.size)
                    input.skipFully(header.size.paddingSize())
                    if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                        target.setLastModified(header.modTime * 1000)
                    }
                    entries++
                    onProgress(
                        RootfsInstallProgress(
                            stage = RootfsInstallStage.EXTRACTING,
                            entriesExtracted = entries,
                            currentEntry = header.name,
                        )
                    )
                }
                require(pendingName == null && pendingLinkName == null) { "Incomplete tar metadata" }
                var padding = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    padding += count
                    require(padding <= MAX_TRAILER_BYTES && (0 until count).all { buffer[it] == 0.toByte() }) { "Unexpected data after tar trailer" }
                }
                val unresolved = hardLinks.associate { (target, source) -> target to targetDir.safeResolve(source) }.toMutableMap()
                var copiedHardLinkBytes = 0L
                for (target in unresolved.keys.toList()) {
                    checkInterrupted()
                    var source = unresolved[target] ?: continue
                    val chain = linkedSetOf(target)
                    while (source in unresolved) {
                        require(chain.add(source)) { "Cyclic Rootfs hard links" }
                        source = unresolved.getValue(source)
                    }
                    require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS)) { "Missing hard link target: ${source.name}" }
                    for (link in chain) {
                        copiedHardLinkBytes += linkRootfsEntry(source, link, maxBytes - extractedBytes - copiedHardLinkBytes)
                        unresolved.remove(link)
                    }
                }
            }
        }
    }

    private fun linkRootfsEntry(source: File, target: File, remainingBytes: Long): Long {
        try {
            Files.createLink(target.toPath(), source.toPath())
            return 0L
        } catch (_: IOException) {
            // Android的SELinux/文件系统可能拒绝link(2)，复制同样能保留Rootfs可用性。
        } catch (_: UnsupportedOperationException) {
        }
        val size = source.length()
        require(size <= remainingBytes) { "Rootfs hard link copy exceeds extraction budget" }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        target.setReadable(source.canRead(), false)
        target.setWritable(source.canWrite(), false)
        target.setExecutable(source.canExecute(), false)
        target.setLastModified(source.lastModified())
        return size
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        require(linkName.isNotBlank() && '\u0000' !in linkName && linkName.length <= 4096) { "Invalid link name" }
        val link = File(linkName).toPath()
        if (!link.isAbsolute) {
            require(requireNotNull(target.parentFile).toPath().resolve(link).normalize().startsWith(root.canonicalFile.toPath())) {
                "Rootfs link leaves its directory"
            }
        }
        Files.createSymbolicLink(target.toPath(), link)
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) {
            require(readExactly(TAR_BLOCK_SIZE.toLong()).all { it == 0.toByte() }) { "Invalid tar trailer" }
            return null
        }
        val expected = header.octal(148, 8)
        val actual = header.indices.sumOf { index -> if (index in 148..155) 32L else (header[index].toInt() and 255).toLong() }
        require(expected == actual) { "Invalid tar header checksum" }

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name).filter { it.isNotBlank() }.joinToString("/")
        return TarHeader(
            name = fullName,
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 0
        while (offset < bytes.size) {
            var space = offset
            while (space < bytes.size && bytes[space] in 48..57) space++
            require(space in (offset + 1) until bytes.size && bytes[space] == 32.toByte()) { "Invalid PAX record" }
            val length = bytes.copyOfRange(offset, space).toString(Charsets.US_ASCII).toIntOrNull() ?: error("Invalid PAX length")
            require(length > space - offset + 2 && length <= bytes.size - offset && bytes[offset + length - 1] == 10.toByte()) { "Invalid PAX length" }
            val record = bytes.copyOfRange(space + 1, offset + length - 1).toString(Charsets.UTF_8)
            val key = record.substringBefore('=')
            if ('=' in record) result[key] = record.substringAfter('=')
            offset += length
        }
        return result
    }

    /** 协程取消时调用方通过runInterruptible把取消转成线程中断，这里尽早退出阻塞循环 */
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes in 0..MAX_METADATA_BYTES) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            checkInterrupted()
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val root = canonicalFile
        val parts = normalizeTarPath(path).split('/').filter { it.isNotEmpty() && it != "." }
        var target = root
        for (part in parts) {
            target = File(target, part)
            require(!Files.isSymbolicLink(target.toPath())) { "Archive entries must not follow links" }
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun normalizeTarPath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && normalized.length <= 4096) { "Invalid Rootfs entry path" }
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length).trim().lowercase(Locale.US).trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE, DIRECTORY, SYMLINK, HARDLINK, LONG_NAME, LONG_LINK, PAX, OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input, 128 * 1024)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 4 * MAX_ARCHIVE_BYTES
        private const val DISK_RESERVE_BYTES = 64L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 64L * 1024
        private const val MAX_TRAILER_BYTES = 1024L * 1024
        private const val MAX_ENTRIES = 150_000
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}

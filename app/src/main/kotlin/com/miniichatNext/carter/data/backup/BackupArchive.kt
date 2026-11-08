package com.miniichatNext.carter.data.backup

import com.miniichatNext.carter.util.AppJson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP先完整解包、校验索引和哈希，再允许恢复数据。 */
internal object BackupArchive {
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000

    fun md5(file: File): String {
        val hash = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun write(folder: File, output: File, version: String, missing: List<String>,
        mode: BackupMode? = null, credentialKey: String? = null, missingFileCount: Int = missing.size): BackupManifest {
        val files = folder.walkTopDown().filter { it.isFile && it != File(folder, "manifest.json") }.map { file ->
            BackupFileIndex(file.relativeTo(folder).invariantSeparatorsPath, file.length(), md5(file))
        }.toList()
        val manifest = BackupManifest(
            formatVersion = if (mode != null) 3 else if (files.any { it.path == "data/snapshot.enc" }) 2 else 1,
            appVersion = version, createdAt = System.currentTimeMillis(), files = files, missingFiles = missing,
            missingFileCount = missingFileCount,
            encrypted = mode == BackupMode.ENCRYPTED,
            backupMode = mode?.name,
            credentialKey = credentialKey,
        )
        File(folder, "manifest.json").writeText(AppJson.json.encodeToString(BackupManifest.serializer(), manifest))
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            (files.map { it.path } + "manifest.json").forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                File(folder, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return manifest
    }

    fun read(input: InputStream, folder: File): BackupManifest {
        folder.mkdirs()
        var total = 0L
        val paths = mutableSetOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path) { "Invalid ZIP path" }
                require(path.split('/').none { it == ".." || it == "." }) { "Invalid ZIP path" }
                require(paths.add(path) && paths.size <= MAX_ENTRIES) { "Duplicate ZIP path or too many entries" }
                require(path == "manifest.json" || path.startsWith("data/")) { "Unknown ZIP entry" }
                val dest = File(folder, path).canonicalFile
                require(dest.path.startsWith(folder.canonicalPath + File.separator)) { "ZIP path outside staging" }
                if ((path == "data/snapshot.json" || path == "data/snapshot.enc") && entry.size > 128L * 1024 * 1024) error("Snapshot too large")
                if (!entry.isDirectory) {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_TOTAL_BYTES) { "Backup exceeds size limit" }
                            if (path == "manifest.json") require(dest.length() + count <= 16 * 1024 * 1024) { "Manifest too large" }
                            if ((path == "data/snapshot.json" || path == "data/snapshot.enc")) require(dest.length() + count <= 128L * 1024 * 1024) { "Snapshot too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val manifestFile = File(folder, "manifest.json")
        require(manifestFile.isFile) { "Missing manifest.json" }
        val manifest = AppJson.json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
        require(manifest.formatVersion in 1..3) { "Unsupported backup version" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size) { "Duplicate manifest path" }
        val indexed = manifest.files.map { it.path }.toSet()
        val actual = folder.walkTopDown().filter { it.isFile && it != manifestFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toSet()
        require(indexed == actual) { "Backup index mismatch" }
        if (manifest.formatVersion == 3) {
            when (manifest.backupMode) {
                BackupMode.ENCRYPTED.name -> {
                    require(manifest.encrypted && manifest.credentialKey == null && indexed == setOf("data/payload.enc")) { "Invalid encrypted backup index" }
                }
                BackupMode.QUICK.name -> {
                    require(!manifest.encrypted && "data/snapshot.json" in indexed && "data/payload.enc" !in indexed && "data/snapshot.enc" !in indexed) { "Invalid quick backup index" }
                    BackupCredentials.validateKey(requireNotNull(manifest.credentialKey) { "Missing credential recovery key" })
                }
                else -> error("Unknown backup mode")
            }
        } else {
            require(indexed.count { it == "data/snapshot.json" || it == "data/snapshot.enc" } == 1 && "data/payload.enc" !in indexed) { "Ambiguous snapshot" }
        }
        manifest.files.forEach { index ->
            require(index.path.split('/').none { it == ".." || it == "." }) { "Invalid manifest path" }
            val file = File(folder, index.path).canonicalFile
            require(file.path.startsWith(folder.canonicalPath + File.separator)) { "Manifest path outside staging" }
            require(index.path.startsWith("data/") && file.isFile && file.length() == index.size && md5(file).equals(index.md5, true)) {
                "Backup checksum mismatch: ${index.path}"
            }
        }
        return manifest
    }
}

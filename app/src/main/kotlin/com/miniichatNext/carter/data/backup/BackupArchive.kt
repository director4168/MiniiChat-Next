/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
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
        mode: BackupMode? = null, credentialKey: String? = null, missingFileCount: Int = missing.size, sql: Boolean = false): BackupManifest {
        val files = folder.walkTopDown().filter { it.isFile && it != File(folder, "manifest.json") }.map { file ->
            BackupFileIndex(file.relativeTo(folder).invariantSeparatorsPath, file.length(), md5(file))
        }.toList()
        val manifest = BackupManifest(
            formatVersion = if (sql) SqlBackup.FORMAT else if (mode != null) 3 else if (files.any { it.path == "data/snapshot.enc" }) 2 else 1,
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
                val bounded = path == "data/snapshot.json" || path == "data/snapshot.enc" || (path in LegacyConversationBackup.paths || LegacyConfigurationBackup.namespace(path) != null)
                if (bounded) require(entry.size <= LegacyConversationBackup.MAX_BYTES) { "Backup data file too large" }
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
                            if (bounded) require(dest.length() + count <= LegacyConversationBackup.MAX_BYTES) { "Backup data file too large" }
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
        require(manifest.formatVersion in 1..SqlBackup.FORMAT) { "Unsupported backup version" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size) { "Duplicate manifest path" }
        val indexed = manifest.files.map { it.path }.toSet()
        val actual = folder.walkTopDown().filter { it.isFile && it != manifestFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toSet()
        require(indexed == actual) { "Backup index mismatch" }
        val rawStores = indexed.count { it in LegacyConversationBackup.paths }
        require(rawStores <= 1) { "Ambiguous conversation DataStore files" }
        val configFiles = indexed.mapNotNull(LegacyConfigurationBackup::namespace)
        require(configFiles.distinct().size == configFiles.size) { "Ambiguous configuration DataStore files" }
        require(manifest.formatVersion == SqlBackup.FORMAT || SqlBackup.PATH !in indexed) { "Unexpected backup database" }
        if (manifest.formatVersion >= 3) {
            when (manifest.backupMode) {
                BackupMode.ENCRYPTED.name -> {
                    require(manifest.encrypted && manifest.credentialKey == null && indexed == setOf("data/payload.enc")) { "Invalid encrypted backup index" }
                }
                BackupMode.QUICK.name -> {
                    val primary = if (manifest.formatVersion == SqlBackup.FORMAT) SqlBackup.PATH else "data/snapshot.json"
                    require(!manifest.encrypted && primary in indexed && "data/payload.enc" !in indexed && "data/snapshot.enc" !in indexed) { "Invalid quick backup index" }
                    if (manifest.formatVersion == SqlBackup.FORMAT) require(indexed.all {
                        it == primary || it.startsWith("data/assets/") || it.startsWith("data/skills/")
                    }) { "Unexpected SQL backup files" }
                    BackupCredentials.validateKey(requireNotNull(manifest.credentialKey) { "Missing credential recovery key" })
                }
                else -> error("Unknown backup mode")
            }
        } else {
            val snapshots = indexed.count { it == "data/snapshot.json" || it == "data/snapshot.enc" }
            require(snapshots <= 1 && (snapshots == 1 || rawStores == 1 || configFiles.isNotEmpty()) && "data/payload.enc" !in indexed) { "Ambiguous snapshot" }
            if (snapshots == 0) require(indexed.size == rawStores + configFiles.size && !manifest.encrypted) { "Invalid legacy DataStore backup" }
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

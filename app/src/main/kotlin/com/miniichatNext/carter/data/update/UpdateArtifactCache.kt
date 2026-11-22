/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.publishFile
import com.miniichatNext.carter.util.readBounded
import com.miniichatNext.carter.util.writeAtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class UpdateArtifactCache(private val context: Context) {
    val directory = File(context.filesDir, "updates").also {
        check(!Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.mkdirs())) { "Invalid update cache directory" }
    }
    val apk = File(directory, "update.apk")
    val partial = File(directory, "update.part.apk")
    val failed = File(directory, "failed.apk")
    private val manifest = File(directory, "verified.json")

    fun read(): CachedUpdate? = runCatching {
        AppJson.json.decodeFromString(CachedUpdate.serializer(), Files.newInputStream(manifest.toPath(), NOFOLLOW_LINKS).use { it.readBounded(512 * 1024).toString(Charsets.UTF_8) })
    }.getOrNull()

    /** 以磁盘APK和本次配置为准，索引丢失或地址变化也可重新验证后复用。 */
    suspend fun reusable(info: UpdateInfo, selected: UpdatePackage): CachedUpdate? = cacheMutex.withLock {
        val metadata = read()
        val legacy = File(context.cacheDir, "Update/MiniiChat-Next-update.apk")
        for (candidate in listOf(apk, failed, partial, legacy)) {
            currentCoroutineContext().ensureActive()
            if (!Files.isRegularFile(candidate.toPath(), NOFOLLOW_LINKS) || candidate.length() <= 0) continue
            val actual = try {
                validate(candidate, info, selected)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { continue }
            currentCoroutineContext().ensureActive()
            val result = CachedUpdate(info, selected.abi, selected.downloadUrl, selected.sha256.trim().lowercase(),
                actual, candidate.length(), metadata?.takeIf { it.actualSha256 == actual }?.downloadLocation)
            if (candidate != apk) {
                val staged = Files.createTempFile(directory.toPath(), "reuse-", ".apk").toFile()
                try {
                    candidate.copyTo(staged, overwrite = true)
                    require(sha256(staged) == actual) { "Cached APK changed during recovery" }
                    currentCoroutineContext().ensureActive()
                    publishFile(staged, apk)
                } finally { staged.delete() }
            }
            writeMetadataIfPossible(result)
            return@withLock result
        }
        null
    }

    suspend fun validate(file: File, info: UpdateInfo, selected: UpdatePackage, recordedSha: String? = null): String {
        currentCoroutineContext().ensureActive()
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..(512L * 1024 * 1024)) { "Invalid APK file" }
        // 先读APK实际版本，测试配置与旧包不一致时不把失败包误当作新版缓存。
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("Invalid APK archive")
        val archiveCode = versionCode(version)
        require(info.remoteVersionCode() > 0 && archiveCode == info.remoteVersionCode()) {
            context.getString(R.string.update_apk_version_code_mismatch, info.remoteVersionCode(), archiveCode)
        }
        if (info.versionName.isNotBlank()) require(version.versionName == info.versionName) {
            context.getString(R.string.update_apk_version_name_mismatch, info.versionName, version.versionName.orEmpty())
        }
        val actual = sha256(file)
        require(Regex("[a-fA-F0-9]{64}").matches(selected.sha256.trim())) { "Invalid published SHA-256" }
        require(actual.equals(selected.sha256.trim(), true)) { "SHA-256 mismatch" }
        if (recordedSha != null) require(actual == recordedSha) { "Cached APK was changed" }
        validateInstallable(file, selected)
        return actual
    }

    private fun validateInstallable(file: File, selected: UpdatePackage): PackageInfo {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK archive")
        require(archive.packageName == context.packageName) { "APK package name mismatch" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val current = signers(installed)
        val candidate = signers(archive)
        require(current.isNotEmpty() && candidate == current) { "APK signing certificate mismatch" }
        ZipFile(file).use { zip ->
            val nativeAbis = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
            require(selected.abi in nativeAbis) { "APK architecture mismatch: ${selected.abi}" }
            require(zip.getEntry("AndroidManifest.xml") != null && zip.getEntry("classes.dex") != null) { "Incomplete APK archive" }
        }
        return archive
    }

    suspend fun retainFailed(file: File): Pair<File, String> = cacheMutex.withLock {
        require(file == partial && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val hash = sha256(file)
        currentCoroutineContext().ensureActive()
        publishFile(file, failed)
        failed to hash
    }

    suspend fun validateForForce(file: File, expectedHash: String, selected: UpdatePackage) {
        require(file == failed && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Failed APK is missing" }
        require(expectedHash.isNotBlank() && sha256(file) == expectedHash) { "Downloaded APK was changed" }
        validateInstallable(file, selected)
    }

    suspend fun commit(info: UpdateInfo, selected: UpdatePackage, location: String? = null): CachedUpdate = cacheMutex.withLock {
        val hash = try { validate(partial, info, selected) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { throw UpdateVerificationFailure(partial, error) }
        currentCoroutineContext().ensureActive()
        val result = CachedUpdate(info, selected.abi, selected.downloadUrl, selected.sha256.trim().lowercase(), hash, partial.length(), location)
        publishFile(partial, apk)
        writeMetadataIfPossible(result)
        result
    }

    private fun writeMetadataIfPossible(metadata: CachedUpdate) {
        try { write(metadata) } catch (error: java.io.IOException) {
            DebugLog.w("Update", "APK verified; metadata will be rebuilt: ${error.javaClass.simpleName}")
        }
    }

    fun write(metadata: CachedUpdate) {
        writeAtomicFile(manifest) { it.writeText(AppJson.json.encodeToString(CachedUpdate.serializer(), metadata)) }
    }

    companion object {
        private val cacheMutex = Mutex()

        private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()

        private fun signers(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        suspend fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

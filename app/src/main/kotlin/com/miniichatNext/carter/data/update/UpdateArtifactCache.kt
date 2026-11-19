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
import com.miniichatNext.carter.util.AppJson
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UpdateArtifactCache(private val context: Context) {
    val directory = File(context.filesDir, "updates").apply { mkdirs() }
    val apk = File(directory, "update.apk")
    val partial = File(directory, "update.part.apk")
    val failed = File(directory, "failed.apk")
    private val manifest = File(directory, "verified.json")

    fun read(): CachedUpdate? = runCatching {
        AppJson.json.decodeFromString(CachedUpdate.serializer(), manifest.readText())
    }.getOrNull()

    /** 以磁盘APK和本次配置为准，索引丢失或地址变化也可重新验证后复用。 */
    suspend fun reusable(info: UpdateInfo, selected: UpdatePackage): CachedUpdate? {
        val metadata = read()
        val legacy = File(context.cacheDir, "Update/MiniiChat-Next-update.apk")
        for (candidate in listOf(apk, failed, partial, legacy)) {
            currentCoroutineContext().ensureActive()
            if (!candidate.isFile || candidate.length() <= 0) continue
            val actual = try {
                validate(candidate, info, selected)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { continue }
            currentCoroutineContext().ensureActive()
            val result = CachedUpdate(info, selected.abi, selected.downloadUrl, selected.sha256.trim().lowercase(),
                actual, candidate.length(), metadata?.takeIf { it.actualSha256 == actual }?.downloadLocation)
            if (candidate != apk) {
                val staged = File(directory, "reuse.pending.apk")
                try {
                    candidate.copyTo(staged, overwrite = true)
                    require(sha256(staged) == actual) { "Cached APK changed during recovery" }
                    currentCoroutineContext().ensureActive()
                    check(staged.renameTo(apk)) { "Could not recover cached APK" }
                } finally { staged.delete() }
            }
            write(result)
            return result
        }
        return null
    }

    suspend fun validate(file: File, info: UpdateInfo, selected: UpdatePackage, recordedSha: String? = null): String {
        currentCoroutineContext().ensureActive()
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

    suspend fun retainFailed(file: File): Pair<File, String> {
        require(file.canonicalFile == partial.canonicalFile && file.isFile)
        val hash = sha256(file)
        check(!failed.exists() || failed.delete())
        check(file.renameTo(failed)) { "Could not retain downloaded APK" }
        return failed to hash
    }

    suspend fun validateForForce(file: File, expectedHash: String, selected: UpdatePackage) {
        require(file.canonicalFile == failed.canonicalFile && file.isFile) { "Failed APK is missing" }
        require(expectedHash.isNotBlank() && sha256(file) == expectedHash) { "Downloaded APK was changed" }
        validateInstallable(file, selected)
    }

    suspend fun commit(info: UpdateInfo, selected: UpdatePackage, location: String? = null): CachedUpdate {
        val hash = validate(partial, info, selected)
        currentCoroutineContext().ensureActive()
        val result = CachedUpdate(info, selected.abi, selected.downloadUrl, selected.sha256.trim().lowercase(), hash, partial.length(), location)
        check(!apk.exists() || apk.delete()) { "Could not replace cached APK" }
        check(partial.renameTo(apk)) { "Could not commit verified APK" }
        write(result)
        return result
    }

    fun write(metadata: CachedUpdate) {
        val temporary = File(directory, "verified.json.tmp")
        try {
            temporary.writeText(AppJson.json.encodeToString(CachedUpdate.serializer(), metadata))
            check(temporary.renameTo(manifest)) { "Could not commit APK metadata" }
        } finally { temporary.delete() }
    }

    companion object {

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
            file.inputStream().use { input ->
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

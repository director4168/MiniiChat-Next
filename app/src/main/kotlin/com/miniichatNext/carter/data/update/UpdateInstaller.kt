package com.miniichatNext.carter.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import androidx.core.content.FileProvider
import com.miniichatNext.carter.debug.DebugLog
import java.io.File

/**
 * 安装APK+缓存管理。
 * 安装走系统安装器（Intent.ACTION_VIEW+FileProvider URI）。
 */
object UpdateInstaller {

    /** 缓存目录（context.cacheDir/Update，对应file_paths.xml中的cache-path update_apk） */
    fun cacheDir(context: Context): File =
        File(context.cacheDir, "Update").apply { mkdirs() }

    /** APK在缓存目录里的固定文件名（中间文件：下载完成即移入系统Download） */
    const val APK_FILENAME = "MiniiChat-Next-update.apk"

    fun cacheApk(context: Context): File = File(cacheDir(context), APK_FILENAME)

    /** 复制到系统Download作为用户可管理的副本；返回content URI或API<29文件URI。 */
    fun publishToDownloads(context: Context, installFile: File, version: String): String {
        require(installFile.isFile) { "Update APK is missing: ${installFile.absolutePath}" }
        val name = "MiniiChat-Next-$version.apk"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create update file in Downloads")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    installFile.inputStream().use { it.copyTo(output) }
                } ?: error("Could not write update file in Downloads")
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(context.contentResolver.update(uri, published, null, null) == 1) { "Could not publish update file" }
                return uri.toString()
            } catch (failure: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw failure
            }
        }
        @Suppress("DEPRECATION")
        val publicDirectory = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val canWritePublic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val directory = if (canWritePublic) publicDirectory else {
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "UpdateDownloads")
        }
        check(directory.exists() || directory.mkdirs()) { "Could not create Downloads directory" }
        val destination = File(directory, name)
        installFile.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(destination).toString()
    }

    /**
     * 启动系统安装器（用户确认后安装）。
     * 注意：调用方需要负责安装后清理缓存APK（建议onResume里清）。
     */
    fun install(context: Context, apkFile: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                return false
            }
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            DebugLog.e("Update", "install failed", t)
            false
        }
    }

    /** 清空整个Update缓存目录 */
    fun clearCache(context: Context) {
        val dir = cacheDir(context)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) f.listFiles()?.forEach { it.delete() }
            f.delete()
        }
        DebugLog.i("Update", "cleared cache dir: ${dir.absolutePath}")
    }
}
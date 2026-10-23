package com.miniichatNext.carter.data.update

import android.content.Context
import android.content.Intent
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

    /**
     * 最终APK落点：系统下载目录（用户可见、可手动管理）。
     * 更新成功（安装器拉起）后可清理这里的**旧**安装包。
     */
    fun downloadsApk(context: Context, version: String): File {
        val dir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        // 带版本号的文件名：多版本共存时可辨认；同版本重下覆盖
        return File(dir, "MiniiChat-Next-$version.apk")
    }

    /** 清理Download目录里的旧版本安装包（保留指定文件） */
    fun cleanOldDownloads(context: Context, keep: File) {
        runCatching {
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("MiniiChat-Next-") && f.name.endsWith(".apk") && f.absolutePath != keep.absolutePath) {
                    f.delete()
                }
            }
        }
    }

    /**
     * 启动系统安装器（用户确认后安装）。
     * 注意：调用方需要负责安装后清理缓存APK（建议onResume里清）。
     */
    fun install(context: Context, apkFile: File): Boolean {
        return try {
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
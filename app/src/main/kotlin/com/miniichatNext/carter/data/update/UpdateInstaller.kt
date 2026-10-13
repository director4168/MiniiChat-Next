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

    /** APK在缓存目录里的固定文件名 */
    const val APK_FILENAME = "MiniiChat-Next-update.apk"

    fun cacheApk(context: Context): File = File(cacheDir(context), APK_FILENAME)

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
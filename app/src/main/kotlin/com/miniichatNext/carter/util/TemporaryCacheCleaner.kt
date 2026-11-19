/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import android.content.Context
import java.io.File
import java.nio.file.Files

object TemporaryCacheCleaner {
    private const val UPDATE_DIR = "Update"

    /**
     * 清理上次进程遗留的临时内容：日志导出zip、备份stage、图片裁剪缓存、外部打开缓存等。
     * Update目录是用户可能尚未安装的APK，必须保留；filesDir及其子目录从不触碰。
     */
    fun clearOnStartup(context: Context): Int {
        return clearDirectory(context.cacheDir, context.filesDir)
    }

    internal fun clearDirectory(cacheDir: File, filesDir: File): Int {
        if (Files.isSymbolicLink(cacheDir.toPath())) return 0
        val cache = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return 0
        val files = runCatching { filesDir.canonicalFile }.getOrNull() ?: return 0
        if (cache == files || files.path.startsWith(cache.path + File.separator)) return 0
        var removed = 0
        cache.listFiles()?.forEach { file ->
            if (file.name == UPDATE_DIR) return@forEach
            if (file.name == "shared" && file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    if (Files.isSymbolicLink(child.toPath())) return@forEach
                    if (child.isFile && child.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000) {
                        if (child.delete()) removed++
                    } else if (child.isDirectory && child.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000) {
                        if (child.deleteRecursively()) removed++
                    }
                }
                return@forEach
            }
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            val target = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (target.parentFile != cache || target == files || target.path.startsWith(files.path + File.separator)) return@forEach
            if (file.isDirectory && file.walkTopDown().any { Files.isSymbolicLink(it.toPath()) }) return@forEach
            if (file.deleteRecursively()) removed++
        }
        return removed
    }
}

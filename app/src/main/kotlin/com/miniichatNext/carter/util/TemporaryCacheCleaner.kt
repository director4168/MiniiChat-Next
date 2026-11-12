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
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            val target = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (target.parentFile != cache || target == files || target.path.startsWith(files.path + File.separator)) return@forEach
            if (file.isDirectory && file.walkTopDown().any { Files.isSymbolicLink(it.toPath()) }) return@forEach
            if (file.deleteRecursively()) removed++
        }
        return removed
    }
}

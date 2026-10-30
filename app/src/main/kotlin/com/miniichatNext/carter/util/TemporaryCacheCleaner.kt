package com.miniichatNext.carter.util

import android.content.Context
import java.io.File

object TemporaryCacheCleaner {
    private const val UPDATE_DIR = "Update"

    fun clearOnStartup(context: Context): Int {
        val cache = context.cacheDir
        var removed = 0
        cache.listFiles()?.forEach { file ->
            if (file.name == UPDATE_DIR) return@forEach
            if (file.deleteRecursively()) removed++
        }
        return removed
    }
}

package com.miniichatNext.carter.debug.ui

import android.content.Context
import java.io.File

object DebugCropCache {
    private const val DIR = "debug_crop_test"

    fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    fun clear(context: Context) {
        val dir = dir(context)
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }
}

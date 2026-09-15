package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.miniichatNext.carter.debug.DebugLog

/** 背景图解码后的最长边上限：屏幕最大也就~1440px，没必要按原图解码 */
internal const val BG_MAX_DECODE_PX = 1600


@Composable
internal fun rememberBackgroundBitmap(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val file = java.io.File(path)
        var decoded: androidx.compose.ui.graphics.ImageBitmap? = null
        for (attempt in 0 until 3) {
            if (!file.exists() || file.length() <= 0L) {
                kotlinx.coroutines.delay(140L)
                continue
            }
            decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { decodeBackground(path)?.asImageBitmap() }.getOrNull()
            }
            if (decoded != null) break
            kotlinx.coroutines.delay(180L)
        }
        if (decoded == null) {
            com.miniichatNext.carter.debug.DebugLog.w(
                "ChatBg",
                "background image unusable: $path exists=${file.exists()} bytes=${file.length()}"
            )
        } else {
            com.miniichatNext.carter.debug.DebugLog.i(
                "ChatBg",
                "background image loaded: $path ${decoded.width}x${decoded.height}"
            )
        }
        bitmap = decoded
    }
    return bitmap
}

/**
 * 从CSS里提取背景颜色
 * 仅作为WebView渲染失败时的兜底：解析不出来就返回null，完全不影响正常CSS渲染
 */
internal fun parseCssFallbackColors(css: String): List<androidx.compose.ui.graphics.Color>? {
    val declaration = Regex("""background(?:-color|-image)?\s*:\s*([^;}]+)""", RegexOption.IGNORE_CASE)
        .find(css)?.groupValues?.getOrNull(1)?.trim()
        ?: return null
    val colors = Regex("""#([0-9a-fA-F]{3,8})""").findAll(declaration)
        .mapNotNull { parseHexColor(it.groupValues[1]) }
        .toList()
    return colors.ifEmpty { null }
}

internal fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color? = runCatching {
    val expanded = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        else -> hex
    }
    val value = expanded.toLong(16)
    when (expanded.length) {
        6 -> androidx.compose.ui.graphics.Color((0xFF000000L or value).toInt())
        8 -> androidx.compose.ui.graphics.Color(value.toInt())
        else -> null
    }
}.getOrNull()

/** 按最长边不超过[BG_MAX_DECODE_PX]计算inSampleSize后解码 */
internal fun decodeBackground(path: String): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= BG_MAX_DECODE_PX ||
        bounds.outHeight / (sample * 2) >= BG_MAX_DECODE_PX
    ) {
        sample *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return android.graphics.BitmapFactory.decodeFile(path, options)
}

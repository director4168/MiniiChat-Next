package com.miniichatNext.carter.ui.markdown

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

/** jlatexmath原生LaTeX渲染；解析失败回退等宽文本 */

/**
 * 公式Drawable LRU缓存：进入会话时同屏公式可能很多，重复的LaTeX解析+栅格化
 * 是"进对话卡顿"的主要来源之一。key=公式文本+字号+颜色。
 */
private val latexDrawableCache = object : android.util.LruCache<Long, JLatexMathDrawable>(64) {}

private fun latexCacheKey(latex: String, fontSize: Float, color: Int, background: Int = 0): Long {
    var h = latex.hashCode().toLong()
    h = h * 31 + fontSize.toBits()
    h = h * 31 + color
    h = h * 31 + background
    return h
}

fun assumeLatexSize(latex: String, fontSize: Float): Rect = runCatching {
    val key = latexCacheKey(latex, fontSize, 0, 0)
    latexDrawableCache.get(key)?.bounds
        ?: JLatexMathDrawable.builder(latex).textSize(fontSize).padding(0).build()
            .also { latexDrawableCache.put(key, it) }
            .bounds
}.getOrElse { Rect(0, 0, 0, 0) }

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val merged = style.merge(fontSize = fontSize, color = color)
    val effFontSize = merged.fontSize.takeOrElse { 16.sp }
    val effColor = if (merged.color.isSpecified) merged.color else LocalContentColor.current
    val density = LocalDensity.current
    val drawable = remember(latex, effFontSize, effColor) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = latex,
                    fontSize = effFontSize.toPx().coerceAtLeast(1f),
                    color = effColor.toArgb(),
                    background = 0,
                )
            }
        }.onFailure { it.printStackTrace() }.getOrNull()
    }
    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier.size(
                    width = drawable.bounds.width().toDp(),
                    height = drawable.bounds.height().toDp(),
                ),
            ) { drawable.draw(drawContext.canvas.nativeCanvas) }
        }
    } else {
        Text(latex, style = merged, modifier = modifier)
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int,
): JLatexMathDrawable? = runCatching {
    val key = latexCacheKey(latex, fontSize, color, background)
    latexDrawableCache.get(key) ?: JLatexMathDrawable.builder(latex)
        .textSize(fontSize)
        .color(color)
        .background(background)
        .padding(0)
        .align(JLatexMathDrawable.ALIGN_LEFT)
        .build()
        .also { latexDrawableCache.put(key, it) }
}.onFailure { it.printStackTrace() }.getOrNull()

/** 行内公式拆段：按顶层运算符水平拆成多段Drawable（段间可换行）；失败返回空列表 */
private val latexSplitCache = object : android.util.LruCache<Long, List<JLatexMathDrawable>>(48) {}

internal fun splitLatex(latex: String, maxWidthPx: Float, fontSize: Float, color: Int): List<JLatexMathDrawable> {
    var key = latex.hashCode().toLong()
    key = key * 31 + maxWidthPx.toBits()
    key = key * 31 + fontSize.toBits()
    key = key * 31 + color
    latexSplitCache.get(key)?.let { return it }
    val result = runCatching { JLatexMathSplitter.split(latex, maxWidthPx, fontSize, color) }
        .onFailure { it.printStackTrace() }
        .getOrElse { emptyList() }
    if (result.isNotEmpty()) latexSplitCache.put(key, result)
    return result
}

/** 已拆段的公式Drawable渲染（inlineContent占位内容用） */
@Composable
fun LatexDrawable(drawable: JLatexMathDrawable, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp(),
            ),
        ) { drawable.draw(drawContext.canvas.nativeCanvas) }
    }
}

/**
 * 剥掉$..$$..$/\(..\)/\[..\]定界符（jlatexmath只吃纯公式）。
 * 块级公式AST（fork的BLOCK_MATH）文本含$$壳，渲染前必须剥掉。
 */
internal fun processLatex(latex: String): String {
    val t = latex.trim()
    return when {
        DISPLAY_DOLLAR.matches(t) -> DISPLAY_DOLLAR.find(t)?.groupValues?.get(1)?.trim() ?: t
        INLINE_DOLLAR.matches(t) -> INLINE_DOLLAR.find(t)?.groupValues?.get(1)?.trim() ?: t
        DISPLAY_BRACKET.matches(t) -> DISPLAY_BRACKET.find(t)?.groupValues?.get(1)?.trim() ?: t
        INLINE_PAREN.matches(t) -> INLINE_PAREN.find(t)?.groupValues?.get(1)?.trim() ?: t
        else -> t
    }
}

private val DISPLAY_DOLLAR = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val INLINE_DOLLAR = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val DISPLAY_BRACKET = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)
private val INLINE_PAREN = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)

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

fun assumeLatexSize(latex: String, fontSize: Float): Rect = runCatching {
    JLatexMathDrawable.builder(latex).textSize(fontSize).padding(0).build().bounds
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
    JLatexMathDrawable.builder(latex)
        .textSize(fontSize)
        .color(color)
        .background(background)
        .padding(0)
        .align(JLatexMathDrawable.ALIGN_LEFT)
        .build()
}.onFailure { it.printStackTrace() }.getOrNull()

/** 剥掉$..$$..$/\(..\)/\[..\]定界符（jlatexmath只吃纯公式） */
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

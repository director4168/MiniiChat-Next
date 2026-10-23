package com.miniichatNext.carter.ui.markdown

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Markdown渲染入口（对外签名与旧实现一致，调用方零改动）。
 *
 * 内部使用移植自rikkahub的渲染管线：
 * Markdown→JetBrains AST→HTML→Jsoup DOM→不可变块模型→Compose。
 * 块是不可变data class：流式输出时内容未变的块equals相等，Composable整块跳过，
 * 只有真正变化的块重组（这是rikkahub流式不卡顿的核心机制）。
 * 解析在后台线程进行（conflate+Dispatchers.Default），首帧同步解析保证不闪白。
 */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    palette: InlinePalette = BubblePalette,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    val effColor = if (color.isSpecified) color else LocalContentColor.current
    val style = MaterialTheme.typography.bodyLarge
    CompositionLocalProvider(LocalContentColor provides effColor) {
        Markdown(
            content = text,
            modifier = modifier,
            style = style,
            theme = mdTheme(style, palette),
            components = mdComponents(palette, onFullscreen),
        )
    }
}

/** 由气泡调色板派生Markdown主题（链接/行内代码/引用线/列表符号跟随气泡配色） */
@Composable
internal fun mdTheme(style: TextStyle, palette: InlinePalette): MarkdownTheme {
    val base = MarkdownDefaults.theme(style)
    return remember(base, palette) {
        base.copy(
            link = SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline),
            inlineCode = SpanStyle(
                color = palette.inlineCodeFg,
                background = palette.inlineCodeBg,
                fontFamily = FontFamily.Monospace,
                fontSize = 0.9.em,
            ),
            blockSpacing = 10.dp,
            markerColor = palette.link,
            quoteColor = palette.quoteBar,
            dividerColor = palette.outline,
        )
    }
}

/**
 * 渲染插槽：代码块/表格/图片/公式接入本项目的卡片与LaTeX渲染。
 *
 * `onFullscreen`由调用方每次重组新建，若直接当remember的key，MarkdownComponents会每帧换新实例，
 * 导致LocalMarkdownContext失效并让整个文档每帧全量重组（流式时严重卡顿、点击容易丢）。
 * 这里用rememberUpdatedState稳定回调，只在palette变化时重建。
 */
@Composable
internal fun mdComponents(
    palette: InlinePalette,
    onFullscreen: ((String, Boolean) -> Unit)?,
): MarkdownComponents {
    val currentFullscreen by rememberUpdatedState(onFullscreen)
    val hasFullscreen = onFullscreen != null
    return remember(palette, hasFullscreen) {
        MarkdownComponents(
            codeBlock = { block ->
                MdCodeBlockCard(block, palette) { code, dark -> currentFullscreen?.invoke(code, dark) }
            },
            table = { table -> MdTableCard(table, palette) },
            image = { image, link -> MdImageChip(image, link, palette) },
            mathBlock = { formula -> MdMathBlock(formula) },
            inlineMath = { formula -> buildInlineMathWidgets(this, formula) },
        )
    }
}

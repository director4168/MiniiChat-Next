package com.miniichatNext.carter.ui.markdown

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.miniichatNext.carter.R
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.miniichatNext.carter.util.AppThemeOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun AnnotatedString.lineSlice(start: Int, end: Int): AnnotatedString {
    val source = this
    val safeStart = start.coerceIn(0, source.text.length)
    val safeEnd = end.coerceIn(safeStart, source.text.length)
    return buildAnnotatedString {
        append(source.text.substring(safeStart, safeEnd))
        source.spanStyles.forEach { range ->
            val from = maxOf(range.start, safeStart)
            val to = minOf(range.end, safeEnd)
            if (from < to) addStyle(range.item, from - safeStart, to - safeStart)
        }
    }
}

/** 代码块卡片：类型/复制+行号+单Text代码（横向滚动） */
@Composable
internal fun MdCodeBlockCard(
    block: MarkdownBlock.CodeBlock,
    palette: InlinePalette,
    preferences: MarkdownPreferences = LocalMarkdownPreferences.current,
    onFullscreen: (String, Boolean) -> Unit,
) {
    val lang = block.language.orEmpty().trim().substringBefore(' ').lowercase()
    if (lang.equals("mermaid", ignoreCase = true) && block.closed && block.code.isNotBlank()) {
        MermaidChart(block.code, palette, onFullscreen = onFullscreen)
        return
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    val allLines = remember(block.code) { block.code.split('\n') }
    // 折叠态只跟随偏好，不跟随流式中的代码内容（否则每个token都重置，代码框反复折叠/展开）
    var expanded by remember(preferences.codeFold) { mutableStateOf(false) }
    val folded = preferences.codeFold && allLines.size > 10 && !expanded
    val displayedCode = if (folded) allLines.take(10).joinToString("\n") else block.code
    val highlighted by produceState(
        initialValue = AnnotatedString(displayedCode),
        displayedCode,
        lang,
        palette.codeHighlight,
    ) {
        value = withContext(Dispatchers.Default) {
            val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
            val result = runCatching { highlightCode(displayedCode, lang, palette.codeHighlight) }
                .getOrDefault(AnnotatedString(displayedCode))
            val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
            if (elapsedMs >= 16L) {
                android.util.Log.w("ChatJank", "codeHighlightMs=$elapsedMs chars=${displayedCode.length} lang=$lang")
            }
            result
        }
    }
    val highlightedLines = remember(highlighted, displayedCode) {
        buildList<AnnotatedString> {
            var start = 0
            displayedCode.split('\n').forEach { line ->
                val end = (start + line.length).coerceAtMost(highlighted.length)
                add(highlighted.lineSlice(start, end))
                start = (end + 1).coerceAtMost(highlighted.length)
            }
        }
    }
    val lineCount = highlightedLines.size
    val lineNumberWidth = lineCount.toString().length
    val dark = LocalDarkMode.current
    val overrideVersion = AppThemeOverrides.version.intValue
    val foldActionColor = remember(overrideVersion, dark) {
        val mode = if (dark) "dark" else "light"
        AppThemeOverrides.customColor(
            AppThemeOverrides.load(context)[mode].orEmpty(),
            "toastWarningAction",
            Color(0xFFB1A4FF),
        )
    }

    ContentCard(
        label = lang.takeIf { it.isNotBlank() && !it.equals("plaintext", ignoreCase = true) }
            ?: stringResource(R.string.markdown_code),
        palette = palette,
        actions = {
            if (block.closed) {
                CardActionButton(
                    icon = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = palette.langFg,
                ) {
                    clipboard.setText(AnnotatedString(block.code))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }
            }
        },
    ) {
        if (preferences.codeWrap) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                highlightedLines.forEachIndexed { index, line ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                            style = codeStyle,
                            color = palette.lineNumberFg,
                            softWrap = false,
                            modifier = Modifier.padding(start = 10.dp, end = 8.dp),
                        )
                        Text(
                            text = if (line.length == 0) AnnotatedString(" ") else line,
                            style = codeStyle.copy(color = palette.codeFg),
                            minLines = 1,
                            softWrap = true,
                            modifier = Modifier.weight(1f).padding(start = 2.dp, end = 12.dp),
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 10.dp)
            ) {
                Text(
                    text = (1..lineCount).joinToString("\n") { it.toString().padStart(lineNumberWidth, ' ') },
                    style = codeStyle,
                    color = palette.lineNumberFg,
                    softWrap = false,
                    modifier = Modifier
                        .drawBehind {
                            val x = size.width - 0.5.dp.toPx()
                            drawLine(
                                color = palette.outline,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .padding(start = 10.dp, end = 8.dp),
                )
                Text(
                    text = highlighted,
                    style = codeStyle.copy(color = palette.codeFg),
                    softWrap = false,
                    modifier = Modifier.padding(start = 2.dp, end = 12.dp),
                )
            }
        }
        if (preferences.codeFold && allLines.size > 10) {
            TextButton(
                onClick = { expanded = !expanded },
                colors = ButtonDefaults.textButtonColors(contentColor = foldActionColor),
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                    tint = foldActionColor,
                )
                Text(
                    if (expanded) stringResource(R.string.preference_code_fold_less)
                    else stringResource(R.string.preference_code_fold_more, allLines.size - 10),
                )
            }
        }
    }
}

/** 表格卡片：类型/复制markdown+表格本体 */
@Composable
internal fun MdTableCard(table: MarkdownBlock.Table, palette: InlinePalette) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val markdown = table.source
    ContentCard(
        label = stringResource(R.string.markdown_table),
        palette = palette,
        actions = {
            if (!markdown.isNullOrBlank()) {
                CardActionButton(
                    icon = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = palette.langFg,
                ) {
                    clipboard.setText(AnnotatedString(markdown))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }
            }
        },
    ) {
        MarkdownDefaults.Table(
            table = table,
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            outerBorder = null,
            shape = RectangleShape,
            // 行头/框线跟随气泡调色板：默认的Material主题色在固定深色卡片里会过亮或隐形
            headerBackground = palette.cardHeaderBg,
            cellBorder = BorderStroke(0.5.dp, palette.outline.copy(alpha = 0.55f)),
        )
    }
}

/**
 * 块级公式：直接采用rikkahub MathBlock结构——窄公式居中，宽公式水平滚动。
 * 不在主线程预先测量或缩放，避免极长公式测量本身拖住消息列表。
 */
@Composable
internal fun MdMathBlock(formula: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LatexText(
            latex = formula,
            color = LocalContentColor.current,
            fontSize = LocalTextStyle.current.fontSize,
            modifier = Modifier
                .align(Alignment.Center)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/** 图片：无图片加载库，显示为可点击的替代文本小卡 */
@Composable
internal fun MdImageChip(image: MarkdownInline.Image, link: String?, palette: InlinePalette) {
    val uriHandler = LocalUriHandler.current
    Surface(
        onClick = { runCatching { uriHandler.openUri(link ?: image.url) } },
        shape = RoundedCornerShape(8.dp),
        color = palette.codeBg,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = palette.link,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = image.alt ?: image.url,
                color = palette.link,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 行内公式拆段：返回多段即允许在文本流中换行（长公式不会把气泡撑破）。
 * 拆分失败时回退为单体占位；连尺寸都测不出时返回空列表，由渲染层降级为等宽源码。
 */
internal fun buildInlineMathWidgets(
    scope: MarkdownInlineScope,
    formula: String,
): List<MarkdownInlineWidget> {
    val density = scope.density
    val fontSizePx = with(density) { scope.style.fontSize.takeOrElse { 16.sp }.toPx() }
    val colorArgb = scope.contentColor.toArgb()
    val drawables = splitLatex(formula, fontSizePx * 2f, fontSizePx, colorArgb)
    if (drawables.isEmpty()) {
        val rect = runCatching { assumeLatexSize(processLatex(formula.trim()), fontSizePx) }
            .getOrDefault(android.graphics.Rect(0, 0, 0, 0))
        if (rect.width() <= 0 || rect.height() <= 0) return emptyList()
        return listOf(
            MarkdownInlineWidget(
                placeholder = Placeholder(
                    width = with(density) { rect.width().toSp() },
                    height = with(density) { rect.height().toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                LatexText(latex = formula, color = scope.contentColor)
            }
        )
    }
    return drawables.map { drawable ->
        MarkdownInlineWidget(
            placeholder = Placeholder(
                width = with(density) { drawable.bounds.width().toSp() },
                height = with(density) { drawable.bounds.height().toSp() },
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            LatexDrawable(drawable = drawable)
        }
    }
}

/** 供弹窗标题等纯文本场景使用的内联渲染（保留旧签名） */
@Composable
internal fun InlineMarkdownText(
    text: String,
    palette: InlinePalette,
    onLink: (String) -> Unit = {},
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val paragraph = remember(text) {
        runCatching { parseMarkdown(text) }.getOrNull()
            ?.blocks?.firstOrNull() as? MarkdownBlock.Paragraph
    }
    val theme = mdTheme(style, palette)
    val components = mdComponents(palette, null)
    val context = remember(theme, components) { MarkdownContext(theme, components, null) }
    CompositionLocalProvider(
        LocalMarkdownContext provides context,
        LocalContentColor provides color,
    ) {
        ProvideTextStyle(style.copy(textAlign = textAlign ?: style.textAlign)) {
            val inline = paragraph?.content
            if (inline.isNullOrEmpty()) {
                Text(text = text, modifier = modifier)
            } else {
                InlineText(inline, modifier)
            }
        }
    }
}

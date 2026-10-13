package com.miniichatNext.carter.ui.markdown

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R

@Composable
internal fun MarkdownBlocks(
    blocks: List<Block>,
    palette: InlinePalette,
    textColor: Color,
    onLink: (String) -> Unit,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    Column {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) {
                Spacer(Modifier.height(when {
                    block is Block.CodeBlock || blocks[idx - 1] is Block.CodeBlock -> 8.dp
                    block is Block.Table || blocks[idx - 1] is Block.Table -> 10.dp
                    // 相邻段落=空行分隔的段落：给足段距，否则"两个\n"看起来像只换了一行
                    block is Block.Paragraph && blocks[idx - 1] is Block.Paragraph -> 16.dp
                    else -> 6.dp
                }))
            }
            when (block) {
                is Block.Heading -> RenderHeading(block, textColor, palette, onLink)
                is Block.Paragraph -> RenderParagraph(block, textColor, palette, onLink)
                is Block.BulletItem -> RenderBulletItem(block, textColor, palette, onLink)
                is Block.NumberedItem -> RenderNumberedItem(block, textColor, palette, onLink)
                is Block.Quote -> RenderQuote(block, palette, onLink)
                is Block.Hr -> RenderHr(palette)
                is Block.CodeBlock -> RenderCodeBlock(block, palette, onLink, onFullscreen)
                is Block.Table -> RenderTable(block, palette, textColor, onLink)
                is Block.MathBlock -> RenderMathBlock(block, textColor)
            }
        }
    }
}

@Composable
private fun RenderHeading(
    block: Block.Heading,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
        else -> MaterialTheme.typography.titleMedium
    }
    InlineMarkdownText(block.text, palette, onLink, color = textColor, style = style)
}

@Composable
private fun RenderParagraph(
    block: Block.Paragraph,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) = InlineMarkdownText(
    block.text, palette, onLink,
    color = textColor,
    style = MaterialTheme.typography.bodyLarge
)

@Composable
private fun RenderBulletItem(
    block: Block.BulletItem,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(20.dp)) {
            Text("•", color = textColor, style = MaterialTheme.typography.bodyLarge)
        }
        InlineMarkdownText(
            block.text, palette, onLink,
            color = textColor,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RenderNumberedItem(
    block: Block.NumberedItem,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(28.dp)) {
            Text(
                text = "${block.index}.",
                color = textColor,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
            )
        }
        InlineMarkdownText(
            block.text, palette, onLink,
            color = textColor,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RenderQuote(
    block: Block.Quote,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(palette.quoteBar)
        )
        Spacer(Modifier.width(8.dp))
        InlineMarkdownText(
            block.text, palette, onLink,
            color = palette.quoteFg,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
        )
    }
}

@Composable
private fun RenderHr(palette: InlinePalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.outline)
    )
}

@Composable
private fun RenderCodeBlock(
    block: Block.CodeBlock,
    palette: InlinePalette,
    onLink: (String) -> Unit,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    // mermaid代码块交给WebView渲染图表（自带工具栏：复制/查看源码/全屏预览）
    if (block.lang.equals("mermaid", ignoreCase = true) && block.code.isNotBlank()) {
        MermaidChart(block.code, palette, onFullscreen = onFullscreen)
        return
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.codeBg)
            .border(1.dp, palette.outline, RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (block.lang.isNotBlank()) {
                Text(
                    block.lang,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = palette.langFg,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.outline)
                )
            }
            // 代码块背景固定深色（BubblePalette.codeBg），配套用深色高亮
            val codeStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
            val lines = remember(block.code, block.lang, palette.codeHighlight) {
                highlightCode(block.code, block.lang, palette.codeHighlight).lineSlices()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                // 行号栏：放在横向滚动之外，滚动代码时保持不动
                Column(
                    modifier = Modifier
                        .drawBehind {
                            val x = size.width - 0.5.dp.toPx()
                            drawLine(
                                color = palette.outline,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        .padding(start = 10.dp, end = 8.dp)
                ) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = "${index + 1}",
                            style = codeStyle,
                            color = palette.lineNumberFg,
                            textAlign = TextAlign.End
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 12.dp)
                ) {
                    lines.forEach { line ->
                        Text(text = line, style = codeStyle.copy(color = palette.codeFg))
                    }
                }
            }
        }
        // 右上角复制按钮：不受代码滚动影响
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(block.code))
                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(36.dp),
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.copy),
                tint = palette.codeFg,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 把高亮结果按行切开，便于逐行渲染行号（保留每行的span样式） */
private fun AnnotatedString.lineSlices(): List<AnnotatedString> {
    val raw = text
    if (raw.isEmpty()) return listOf(AnnotatedString(""))
    val out = mutableListOf<AnnotatedString>()
    var start = 0
    while (true) {
        val nl = raw.indexOf('\n', start)
        val end = if (nl < 0) raw.length else nl
        val builder = AnnotatedString.Builder().apply { append(raw.substring(start, end)) }
        spanStyles.forEach { span ->
            if (span.start < end && span.end > start) {
                builder.addStyle(span.item, maxOf(span.start, start) - start, minOf(span.end, end) - start)
            }
        }
        out += builder.toAnnotatedString()
        if (nl < 0) break
        start = nl + 1
    }
    return out
}

@Composable
private fun RenderTable(
    block: Block.Table,
    palette: InlinePalette,
    textColor: Color,
    onLink: (String) -> Unit
) {
    // 外层横向滚动；所有行共用同一套列宽（否则错位）
    val scrollState = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellHPad = 10.dp
    val cellVPad = 8.dp
    val cellStyle = MaterialTheme.typography.bodyMedium
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.Bold)
    val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    // 每列取表头+数据行的最大宽度，且不小于最小列宽
    val colWidths = remember(block, cellStyle) {
        (0 until colCount).map { ci ->
            val cells = buildList {
                block.headers.getOrNull(ci)?.let { add(it) }
                block.rows.forEach { r -> r.getOrNull(ci)?.let { add(it) } }
            }
            val measuredPx = cells.maxOfOrNull { c ->
                measurer.measure(AnnotatedString(c), headerStyle).size.width
            } ?: 0
            val totalPx = measuredPx + with(density) { (cellHPad * 2).toPx() }.toInt()
            with(density) { maxOf(totalPx.toDp(), MIN_COL_WIDTH) }
        }
    }
    // 横线宽度必须显式给出：horizontalScroll内maxWidth=∞，fillMaxWidth()会退化成0
    val tableWidth = colWidths.fold(0.dp) { acc, w -> acc + w } + maxOf(colCount - 1, 0).dp
    // 外层只负责横向滚动；clip+border放在宽度=tableWidth的内层Column
    // border画在滚动容器边界上不随内容延伸，宽表右侧边框会"消失"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
    ) {
        Column(
            modifier = Modifier
                .width(tableWidth)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, textColor.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
        ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            for (ci in 0 until colCount) {
                Box(
                    modifier = Modifier
                        .width(colWidths[ci])
                        .background(textColor.copy(alpha = 0.10f))
                        .padding(horizontal = cellHPad, vertical = cellVPad)
                ) {
                    InlineMarkdownText(
                        block.headers.getOrNull(ci).orEmpty(), palette, onLink,
                        color = textColor,
                        style = headerStyle,
                    )
                }
                if (ci < colCount - 1) CellDivider(textColor)
            }
        }
        Box(modifier = Modifier.width(tableWidth).height(1.dp).background(textColor.copy(alpha = 0.28f)))
        block.rows.forEachIndexed { rowIdx, row ->
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                for (ci in 0 until colCount) {
                    Box(
                        modifier = Modifier
                            .width(colWidths[ci])
                            .padding(horizontal = cellHPad, vertical = cellVPad)
                    ) {
                        InlineMarkdownText(
                            row.getOrNull(ci).orEmpty(), palette, onLink,
                            color = textColor,
                            style = cellStyle,
                        )
                    }
                    if (ci < colCount - 1) CellDivider(textColor)
                }
            }
            if (rowIdx < block.rows.lastIndex) {
                Box(modifier = Modifier.width(tableWidth).height(1.dp).background(textColor.copy(alpha = 0.28f)))
            }
        }
        }
    }
}

private val MIN_COL_WIDTH = 88.dp

@Composable
private fun CellDivider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color.copy(alpha = 0.28f))
    )
}

@Composable
private fun RenderMathBlock(block: Block.MathBlock, textColor: Color) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        LatexText(latex = block.latex, color = textColor)
    }
}

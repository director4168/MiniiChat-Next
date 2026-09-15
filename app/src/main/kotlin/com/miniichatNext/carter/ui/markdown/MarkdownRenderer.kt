package com.miniichatNext.carter.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MarkdownBlocks(
    blocks: List<Block>,
    palette: InlinePalette,
    textColor: Color,
    onLink: (String) -> Unit
) {
    Column {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) {
                Spacer(Modifier.height(when {
                    block is Block.CodeBlock || blocks[idx - 1] is Block.CodeBlock -> 8.dp
                    block is Block.Table || blocks[idx - 1] is Block.Table -> 10.dp
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
                is Block.CodeBlock -> RenderCodeBlock(block, palette, onLink)
                is Block.Table -> RenderTable(block, palette, onLink)
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
    Text(parseInline(block.text, palette, onLink), color = textColor, style = style)
}

@Composable
private fun RenderParagraph(
    block: Block.Paragraph,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) = Text(
    parseInline(block.text, palette, onLink),
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
        Text(
            parseInline(block.text, palette, onLink),
            modifier = Modifier.weight(1f),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge
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
        Text(
            parseInline(block.text, palette, onLink),
            modifier = Modifier.weight(1f),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge
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
        Text(
            parseInline(block.text, palette, onLink),
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
    onLink: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.codeBg)
            .border(1.dp, palette.outline, RoundedCornerShape(10.dp))
    ) {
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
        val highlightPalette = HighlightPalette.Dark
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = highlightCode(block.code, block.lang, highlightPalette),
                color = palette.codeFg,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )
        }
    }
}

@Composable
private fun RenderTable(
    block: Block.Table,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(8.dp))
    ) {
        // 表头：加底色和列间竖线
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBg)
                .height(IntrinsicSize.Min)
        ) {
            block.headers.forEachIndexed { i, cell ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        parseInline(cell, palette, onLink),
                        color = palette.headerFg,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                if (i < block.headers.lastIndex) CellDivider(palette)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.outline))
        // 数据行：行间横线和列间竖线
        block.rows.forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                row.forEachIndexed { i, cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            parseInline(cell, palette, onLink),
                            color = palette.cellFg,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (i < row.lastIndex) CellDivider(palette)
                }
            }
            if (rowIdx < block.rows.lastIndex) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.outline))
            }
        }
    }
}

@Composable
private fun CellDivider(palette: InlinePalette) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(palette.outline)
    )
}

package com.miniichatNext.carter.ui.markdown

import com.miniichatNext.carter.ui.components.clipDrawing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 内容卡片：上层=类型标签+功能键，下层=内容。
 *
 * ┌──────────────┐
 * │类型功能键│←头部（cardHeaderBg，不与内容滚动互相干扰）
 * ├──────────────┤
 * │内容│←内容区（代码/Mermaid/表格）
 * └──────────────┘
 *
 * 功能键固定在上层，避免此前"悬浮在内容右上角"时被长内容推走/被滚动容器截获的问题。
 */
@Composable
internal fun ContentCard(
    label: String,
    palette: InlinePalette,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipDrawing(RoundedCornerShape(10.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(10.dp))
            .background(palette.codeBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardHeaderBg)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = palette.langFg,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // weight占满剩余宽度，功能键才能贴右（此前是fill=false+Spacer，键会停在偏中）
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.outline)
        )
        content()
    }
}

/** 卡片功能键：圆形浅底+图标（与模型选择/加号菜单同款背景框） */
@Composable
internal fun CardActionButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .clickable {
                com.miniichatNext.carter.debug.DebugLog.i("Markdown", "card action tapped: " + (contentDescription ?: ""))
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

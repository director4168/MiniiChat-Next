package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.vm.regenerate

@Composable
internal fun SuggestionPanel(
    conversation: Conversation?,
    page: Int,
    loading: Boolean,
    onPageChange: (Int) -> Unit,
    onGenerate: (Int) -> Unit,
    onPick: (String) -> Unit,
    onEdit: (String) -> Unit
) {
    val current = conversation
    val stored = current?.suggestionPages?.get(page)
        ?: if ((current?.suggestionSeed ?: -1) == page) current?.chatSuggestions.orEmpty() else emptyList()
    val showStored = stored.isNotEmpty()

    // 首次进入页面时自动生成
    LaunchedEffect(page, showStored) {
        if (!showStored) onGenerate(page)
    }

    // 内嵌面板（与菜单面板同一套展示方式，不再是弹窗）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部：标题+重新生成（生成中禁用）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.reply_candidates),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !loading) { onGenerate(page) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.regenerate),
                    modifier = Modifier.size(16.dp),
                    tint = if (loading) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        if (loading) {
            // 生成中：4条骨架+从左到右循环扫过的高光渐变
            repeat(4) { ShimmerSlot() }
            Text(
                stringResource(R.string.suggestion_generating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 每条候选：左编辑按钮+细线隔断+右侧正文
            // 点正文直接发送，点编辑放进输入框并聚焦（弹键盘、关面板），参考“星野”的逻辑
            stored.take(4).forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable { onEdit(candidate) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Text(
                        text = candidate,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(candidate) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!showStored) {
                Text(
                    stringResource(R.string.suggestion_generate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 底部：右侧‹ n/12 ›翻页
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.TextButton(
                    onClick = { if (page > 0) onPageChange(page - 1) },
                    enabled = page > 0
                ) { Text("‹") }
                Text(
                    "${page + 1} / 12",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.TextButton(
                    onClick = { if (page < 11) onPageChange(page + 1) },
                    enabled = page < 11
                ) { Text("›") }
            }
        }
    }
}

/** 候选词骨架条：底色+从左到右循环扫过的高光渐变（波浪渐变条） */
@Composable
internal fun ShimmerSlot(height: androidx.compose.ui.unit.Dp = 40.dp) {
    val transition = rememberInfiniteTransition(label = "suggestion-shimmer")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "suggestion-shimmer-sweep"
    )
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(base)
            .drawWithContent {
                drawContent()
                val w = size.width
                val band = w * 0.55f
                val x = -band + (w + band) * sweep
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + band, 0f)
                    ),
                    size = size
                )
            }
    )
}

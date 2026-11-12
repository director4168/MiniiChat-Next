/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.ui.settings.LocalAppPreferences
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * 显示消息的技术统计信息（如token使用量），点击查看详情
 *
 * 多轮工具调用时usage是各步累计值，行内只显示最后一次请求的上下文占用，累计值放在详情里。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatMessageNerdLine(
    message: Message,
    modifier: Modifier = Modifier,
    contextLength: Int? = null,
    generating: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    val settings = LocalAppPreferences.current
    val real = message.usage?.takeUnless { it.estimated }
    val estimatedCompletion = if (generating) {
        estimateTokens(message.reasoningContent.orEmpty() + message.content)
    } else 0
    val usage = real?.let { current ->
        if (generating) {
            // 工具调用后的新请求可能先回一帧0值usage，prompt沿用上一次已知上下文
            val prompt = current.promptTokens.takeIf { it > 0 } ?: message.lastUsage?.promptTokens ?: 0
            val completion = current.completionTokens.takeIf { it > 0 } ?: estimatedCompletion
            current.copy(
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = prompt + completion,
                estimated = current.estimated || current.completionTokens == 0,
            )
        } else current
    } ?: if (generating) {
        val prompt = message.lastUsage?.promptTokens ?: 0
        TokenUsage(
            promptTokens = prompt,
            completionTokens = estimatedCompletion,
            totalTokens = prompt + estimatedCompletion,
            estimated = true,
        )
    } else null
    if (!settings.showCardTokenUsage || usage == null) return

    val stats = remember(message) { message.nerdStats(usage) }
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                title = { Text(stringResource(R.string.chat_message_usage_title)) },
            ) {
                UsageDetails(stats, contextLength)
            }
        },
        state = tooltipState,
        enableUserInput = false,
        modifier = modifier,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
            CompositionLocalProvider(LocalContentColor provides color) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { tooltipState.show() } }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (stats.steps > 1) {
                        StatsItem(
                            icon = { StatsIcon(Icons.Default.Repeat, stringResource(R.string.chat_message_usage_step_count)) },
                            content = { Text(text = stringResource(R.string.chat_message_usage_steps, stats.steps)) }
                        )
                    }
                    if (stats.tps != null) {
                        val prefix = if (stats.total.estimated) "≈" else ""
                        StatsItem(
                            icon = { StatsIcon(Icons.Default.Bolt, stringResource(R.string.chat_message_usage_speed)) },
                            content = { Text(text = "$prefix${stats.tps.toFixed(1)} tok/s") }
                        )
                    }
                    StatsItem(
                        icon = { StatsIcon(Icons.Default.Layers, stringResource(R.string.chat_message_usage_context)) },
                        content = {
                            val prefix = if (stats.total.estimated) "≈" else ""
                            Text(text = prefix + stringResource(R.string.chat_message_usage_context_short, stats.contextTokens.formatNumber()))
                            val cached = stats.lastUsage.cachedTokens
                            if (cached > 0) {
                                Text(text = stringResource(R.string.chat_message_usage_cached_short, cached.formatNumber()))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageDetails(stats: NerdStats, contextLength: Int?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        val total = stats.total
        ContextUsageBar(
            lastUsage = stats.lastUsage,
            contextLength = contextLength,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (stats.steps > 1) {
            UsageDetailRow(stringResource(R.string.chat_message_usage_step_count), stats.steps.toString())
            UsageDetailRow(stringResource(R.string.chat_message_usage_total_input), total.promptTokens.exact())
            if (total.cachedTokens > 0) UsageDetailRow(stringResource(R.string.chat_message_usage_total_cached), total.cachedTokens.exact())
            UsageDetailRow(stringResource(R.string.chat_message_usage_total_output), total.completionTokens.exact())
        }
        if (stats.tps != null) UsageDetailRow(stringResource(R.string.chat_message_usage_speed), "${stats.tps.toFixed(1)} tok/s")
        if (stats.durationMillis != null) UsageDetailRow(stringResource(R.string.chat_message_usage_duration), "${(stats.durationMillis / 1000f).toFixed(1)}s")
    }
}

private data class UsageSegment(val label: String, val tokens: Int, val color: Color)

/**
 * 最后一次请求的上下文占用：缓存命中、未命中的输入、输出依次排开，剩下的是窗口里的空余
 */
@Composable
private fun ContextUsageBar(
    lastUsage: TokenUsage,
    contextLength: Int?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val segments = listOf(
        UsageSegment(stringResource(R.string.chat_message_usage_cached), lastUsage.cachedTokens, colorScheme.primary),
        UsageSegment(
            stringResource(R.string.chat_message_usage_input),
            (lastUsage.promptTokens - lastUsage.cachedTokens).coerceAtLeast(0),
            colorScheme.primary.copy(alpha = 0.45f),
        ),
        UsageSegment(stringResource(R.string.chat_message_usage_output), lastUsage.completionTokens, colorScheme.tertiary),
    ).filter { it.tokens > 0 }
    val contextTokens = lastUsage.promptTokens + lastUsage.completionTokens
    // 窗口未知或已经超出时，占用本身铺满整条
    val capacity = maxOf(contextLength ?: 0, contextTokens, 1)
    val hasFreeSpace = capacity > contextTokens
    val trackColor = colorScheme.surfaceContainerHighest

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chat_message_usage_context),
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            if (contextLength != null) {
                val fraction = contextTokens.toFloat() / contextLength
                Text(
                    text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()} · ${(fraction * 100).toFixed(0)}%",
                    color = if (fraction >= 0.9f) colorScheme.error else Color.Unspecified,
                )
            } else {
                Text(text = contextTokens.exact())
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val gap = 2.dp.toPx()
            val radius = CornerRadius(size.height / 2)
            // 每段之间留出间隔；还有空余时最后一段和空余之间也要留
            val gaps = if (hasFreeSpace) segments.size else segments.size - 1
            val available = size.width - gap * gaps
            var x = 0f
            segments.forEach { segment ->
                if (x >= size.width) return@forEach
                // 太小的段至少画成一个圆点，不然看不出来
                val width = (segment.tokens.toFloat() / capacity * available).coerceAtLeast(size.height)
                drawRoundRect(
                    color = segment.color,
                    topLeft = Offset(x, 0f),
                    size = Size(width.coerceAtMost(size.width - x), size.height),
                    cornerRadius = radius,
                )
                x += width + gap
            }
            if (hasFreeSpace && x < size.width) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(size.width - x, size.height),
                    cornerRadius = radius,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            segments.forEach { segment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(segment.color, CircleShape)
                    )
                    Text(
                        text = "${segment.label} ${segment.tokens.exact()}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageDetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(text = value)
    }
}

@Composable
private fun StatsIcon(imageVector: ImageVector, contentDescription: String) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(12.dp)
    )
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}

private fun Int.exact(): String = NumberFormat.getIntegerInstance().format(this)

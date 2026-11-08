package com.miniichatNext.carter.ui.chat

import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.TokenUsage

// Ported from rikkahub ChatMessageNerdLine.kt (AGPL-3.0).
internal data class NerdStats(
    val total: TokenUsage,
    val lastUsage: TokenUsage,
    val steps: Int,
    val contextTokens: Int,
    val durationMillis: Long?,
    val tps: Float?,
)

/** 生成中的粗略token估算：CJK按1字1token，其余按4字符1token */
internal fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var wide = 0
    var narrow = 0
    for (ch in text) {
        if (ch.code >= 0x2E80) wide++ else narrow++
    }
    return wide + (narrow + 3) / 4
}

internal fun Message.nerdStats(usage: TokenUsage): NerdStats {
    val last = if (usage.estimated) usage
    else lastRequestUsage()?.takeIf { it.promptTokens + it.completionTokens > 0 } ?: usage
    // 生成中也要显示耗时与速度：generationTimeMs在流式期间持续累加
    val durationMillis = (generationTimeMs ?: finishedAt?.let { it - createdAt })?.takeIf { it > 0 }
    return NerdStats(
        total = usage,
        lastUsage = last,
        steps = stepCount ?: 1,
        contextTokens = last.promptTokens + last.completionTokens,
        durationMillis = durationMillis,
        tps = durationMillis?.let { usage.completionTokens.toFloat() / it * 1000 },
    )
}

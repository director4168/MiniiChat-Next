package com.miniichatNext.carter.data.model

import kotlin.time.Duration

// Ported from rikkahub ai/agent/AgentLoop.kt (AGPL-3.0).
internal fun Message.withStepStats(stepBase: Message, stepTime: Duration): Message {
    // 全零usage只是占位快照，不能覆盖上一步已经拿到的上下文
    val stepUsage = usage?.takeIf { it.promptTokens > 0 || it.completionTokens > 0 || it.cachedTokens > 0 }
    return copy(
        usage = stepBase.usage.sum(stepUsage),
        generationTimeMs = (stepBase.generationTimeMs ?: 0L) + stepTime.inWholeMilliseconds,
        lastUsage = stepUsage ?: stepBase.lastUsage,
        stepCount = (stepBase.stepCount ?: 0) + 1,
    )
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

// Ported from rikkahub ai/core/Usage.kt (AGPL-3.0).
/**
 * 合并同一次请求内的usage快照：各字段取最新的非零值。
 * 跨请求（如多轮工具调用的各个步骤）累计请用[sum]。
 */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}

/**
 * 累加多次请求的usage，每次请求都会按完整上下文计费，因此prompt也直接相加。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.TokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// Ported from rikkahub OpenAI/Responses/Claude provider usage parsers (AGPL-3.0).
internal fun parseTokenUsage(usage: JsonObject): TokenUsage {
    val prompt = usage.count("prompt_tokens", "input_tokens", "promptTokenCount")
    val completion = usage.count("completion_tokens", "output_tokens", "candidatesTokenCount")
    val cached = (usage["prompt_tokens_details"] as? JsonObject)?.countOrNull("cached_tokens")
        ?: (usage["input_tokens_details"] as? JsonObject)?.countOrNull("cached_tokens")
        ?: usage.count("cached_tokens", "prompt_cache_hit_tokens", "cachedContentTokenCount")
    return TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = usage.countOrNull("total_tokens", "totalTokenCount") ?: (prompt + completion),
        cachedTokens = cached,
    )
}

internal fun parseResponsesTokenUsage(usage: JsonObject): TokenUsage = parseTokenUsage(usage)

private fun JsonObject.count(vararg keys: String): Int = countOrNull(*keys) ?: 0

private fun JsonObject.countOrNull(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } }

internal fun parseClaudeTokenUsage(usageJson: JsonObject): TokenUsage {
    val inputTokens = (usageJson["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val cachedInputTokens = (usageJson["cache_read_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val cachedCreationTokens = (usageJson["cache_creation_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val completionTokens = (usageJson["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
    val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
        cachedTokens = cachedInputTokens,
    )
}

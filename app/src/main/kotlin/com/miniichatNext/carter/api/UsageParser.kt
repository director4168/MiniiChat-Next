package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.TokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// Ported from rikkahub OpenAI/Responses/Claude provider usage parsers (AGPL-3.0).
internal fun parseTokenUsage(usage: JsonObject): TokenUsage = TokenUsage(
    promptTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    completionTokens = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    totalTokens = (usage["total_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    cachedTokens = ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull
        ?: (usage["cached_tokens"] as? JsonPrimitive)?.intOrNull
        ?: (usage["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull
        ?: 0,
)

internal fun parseResponsesTokenUsage(usage: JsonObject): TokenUsage = TokenUsage(
    promptTokens = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    completionTokens = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    totalTokens = (usage["total_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    cachedTokens = ((usage["input_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
)

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

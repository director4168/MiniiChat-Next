/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.api.parseTokenUsage
import com.miniichatNext.carter.api.parseResponsesTokenUsage
import com.miniichatNext.carter.api.parseClaudeTokenUsage
import com.miniichatNext.carter.util.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RequestUsageTests {
    @Test fun openAiUsageUsesUpstreamCacheFieldPrecedence() {
        val usage=parseTokenUsage(AppJson.json.parseToJsonElement("""{"prompt_tokens":100,"completion_tokens":30,"total_tokens":130,"prompt_tokens_details":{"cached_tokens":40},"cached_tokens":50,"prompt_cache_hit_tokens":60}""").jsonObject)
        assertEquals(100,usage.promptTokens)
        assertEquals(30,usage.completionTokens)
        assertEquals(130,usage.totalTokens)
        assertEquals(40,usage.cachedTokens)
    }

    @Test fun moonshotAndDeepSeekCacheAreDecoded() {
        assertEquals(50,parseTokenUsage(AppJson.json.parseToJsonElement("""{"cached_tokens":50}""").jsonObject).cachedTokens)
        assertEquals(60,parseTokenUsage(AppJson.json.parseToJsonElement("""{"prompt_cache_hit_tokens":60}""").jsonObject).cachedTokens)
    }

    @Test fun missingFieldsUseUpstreamZeroWithoutLocalGuessing() {
        val usage=parseTokenUsage(AppJson.json.parseToJsonElement("""{"completion_tokens":30,"total_tokens":130}""").jsonObject)
        assertEquals(0,usage.promptTokens)
        assertFalse(usage.estimated)
        assertEquals(130,usage.totalTokens)
    }

    @Test fun responsesUsesInputOutputAndInputDetails() {
        val usage=parseResponsesTokenUsage(AppJson.json.parseToJsonElement("""{"input_tokens":100,"output_tokens":30,"total_tokens":130,"input_tokens_details":{"cached_tokens":40}}""").jsonObject)
        assertEquals(100,usage.promptTokens)
        assertEquals(30,usage.completionTokens)
        assertEquals(40,usage.cachedTokens)
    }

    @Test fun claudeIncludesCacheCreationAndCacheHitsInPromptOnlyOnce() {
        val usage=parseClaudeTokenUsage(AppJson.json.parseToJsonElement("""{"input_tokens":100,"output_tokens":30,"cache_read_input_tokens":40,"cache_creation_input_tokens":50}""").jsonObject)
        assertEquals(190,usage.promptTokens)
        assertEquals(40,usage.cachedTokens)
        assertEquals(220,usage.totalTokens)
    }
}

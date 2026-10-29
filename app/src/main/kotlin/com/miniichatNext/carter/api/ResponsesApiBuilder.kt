package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.util.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun buildResponsesApiRequest(
    provider: ProviderConfig,
    modelId: String,
    messages: List<ChatMessage>,
    stream: Boolean,
    temperature: Float,
    tools: List<ToolSpec> = emptyList(),
    thinking: ThinkingLevel = ThinkingLevel.AUTO,
): String {
    val model = provider.model(modelId)
    val input = buildJsonArray {
        messages.forEach { message ->
            when (message.role) {
                "tool" -> add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", message.toolCallId.orEmpty())
                    put("output", message.content)
                })
                else -> {
                    val parts = message.parts
                    val contentParts = if (parts.isNullOrEmpty()) {
                        listOfNotNull(message.content.takeIf { it.isNotBlank() }.let { text ->
                            text?.let { ChatPart.Text(it) }
                        })
                    } else {
                        parts
                    }
                    val imageParts = contentParts.filterIsInstance<ChatPart.ImageUrl>()
                    val textParts = contentParts.filterIsInstance<ChatPart.Text>()
                    if (textParts.isNotEmpty() || imageParts.isNotEmpty() || message.toolCalls.isNullOrEmpty()) {
                        add(buildJsonObject {
                            put("role", if (message.role == "system") "system" else message.role)
                            if (imageParts.isEmpty()) {
                                put("content", textParts.joinToString("\n\n") { it.text })
                            } else {
                                put("content", buildJsonArray {
                                    textParts.forEach { part -> add(buildJsonObject {
                                        put("type", if (message.role == "assistant") "output_text" else "input_text")
                                        put("text", part.text)
                                    }) }
                                    imageParts.forEach { part -> add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", part.imageUrl.url)
                                        part.imageUrl.detail?.let { put("detail", it) }
                                    }) }
                                })
                            }
                        })
                    }
                    message.toolCalls.orEmpty().forEach { call ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", call.id)
                            put("name", call.name)
                            put("arguments", call.arguments.ifBlank { "{}" })
                        })
                    }
                }
            }
        }
    }
    val level = thinking
    val request = buildJsonObject {
        put("model", modelId)
        put("stream", stream)
        if (level == ThinkingLevel.OFF) put("temperature", model?.temperature ?: temperature)
        put("input", input)
        put("max_output_tokens", model?.maxTokens ?: provider.maxTokens)
        if (provider.supportsReasoning(modelId) && level != ThinkingLevel.AUTO) {
            level.effort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
        }
        if (tools.isNotEmpty()) put("tools", buildJsonArray {
            tools.forEach { tool ->
                val parameters = runCatching { AppJson.json.parseToJsonElement(tool.inputSchema) }
                    .getOrElse { buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) } }
                add(buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", parameters)
                    put("strict", false)
                })
            }
        })
        provider.extraBody.forEach { (key, value) -> if (key.isNotBlank()) put(key, coerceToJson(value)) }
        model?.extraBody.orEmpty().forEach { (key, value) -> if (key.isNotBlank()) put(key, coerceToJson(value)) }
    }
    return AppJson.jsonCompact.encodeToString(JsonObject.serializer(), request)
}

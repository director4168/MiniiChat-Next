package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ResponsesToolCall(
    val outputIndex: Int,
    val callId: String,
    val name: String,
    val arguments: String,
)

/** 按Responses事件增量收集正文、推理、函数参数和token用量。 */
internal class ResponsesApiStreamParser {
    private data class PendingCall(
        var callId: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = linkedMapOf<Int, PendingCall>()
    private var latestUsage: TokenUsage? = null
    private var emittedCalls = false

    fun accept(data: String): List<StreamDelta> {
        if (data.isBlank() || data == "[DONE]") return emptyList()
        val event = runCatching { AppJson.json.parseToJsonElement(data).jsonObject }
            .getOrElse { throw IllegalStateException("Invalid Responses API event", it) }
        val type = event["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val outputIndex = event["output_index"]?.jsonPrimitive?.intOrNull ?: -1
        val emitted = mutableListOf<StreamDelta>()
        when (type) {
            "response.output_text.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    content.append(delta)
                    emitted += StreamDelta(text = delta)
                }
            }
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    reasoning.append(delta)
                    emitted += StreamDelta(reasoning = delta)
                }
            }
            "response.output_item.added", "response.output_item.done" -> {
                val item = event["item"] as? JsonObject
                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" && outputIndex >= 0) {
                    val call = calls.getOrPut(outputIndex) { PendingCall() }
                    call.callId = item.string("call_id") ?: call.callId
                    call.name = item.string("name") ?: call.name
                    item.string("arguments")?.let { args ->
                        if (args.isNotEmpty()) {
                            call.arguments.setLength(0)
                            call.arguments.append(args)
                        }
                    }
                }
            }
            "response.function_call_arguments.delta" -> {
                if (outputIndex >= 0) {
                    val delta = event.string("delta").orEmpty()
                    calls.getOrPut(outputIndex) { PendingCall() }.arguments.append(delta)
                }
            }
            "response.function_call_arguments.done" -> {
                if (outputIndex >= 0) {
                    val call = calls.getOrPut(outputIndex) { PendingCall() }
                    event.string("arguments")?.let { call.arguments.replace(0, call.arguments.length, it) }
                }
            }
            "response.completed", "response.incomplete" -> {
                val response = event["response"] as? JsonObject
                val usage = response?.get("usage") as? JsonObject
                if (usage != null) {
                    val input = usage.int("input_tokens") ?: 0
                    val output = usage.int("output_tokens") ?: 0
                    latestUsage = TokenUsage(promptTokens = input, completionTokens = output, totalTokens = input + output)
                }
                response?.get("output")?.let { output ->
                    val items = output as? JsonArray ?: JsonArray(emptyList())
                    readOutputItems(items)
                    if (content.isEmpty()) readOutputText(items, emitted, reasoning = false)
                    if (reasoning.isEmpty()) readOutputText(items, emitted, reasoning = true)
                }
                latestUsage?.let { emitted += StreamDelta(usage = it) }
            }
            "response.failed", "error" -> {
                val error = event["error"] as? JsonObject
                val detail = error?.string("message") ?: event["message"]?.jsonPrimitive?.contentOrNull
                    ?: event.toString()
                throw IllegalStateException("Responses API $type: ${detail.take(500)}")
            }
        }
        return emitted
    }


    private fun readOutputText(items: JsonArray, output: MutableList<StreamDelta>, reasoning: Boolean) {
        items.forEach { value ->
            val item = value as? JsonObject ?: return@forEach
            if (reasoning && item.string("type") == "reasoning") {
                (item["summary"] as? JsonArray).orEmpty().forEach { blockValue ->
                    val text = (blockValue as? JsonObject)?.string("text") ?: return@forEach
                    if (text.isNotEmpty()) output += StreamDelta(reasoning = text)
                }
            } else if (!reasoning && item.string("type") == "message") {
                (item["content"] as? JsonArray).orEmpty().forEach { blockValue ->
                    val block = blockValue as? JsonObject ?: return@forEach
                    if (block.string("type") in setOf("output_text", "text")) {
                        block.string("text")?.takeIf { it.isNotEmpty() }?.let { output += StreamDelta(text = it) }
                    }
                }
            }
        }
    }

    private fun readOutputItems(items: JsonArray) {
        items.forEachIndexed { index, value ->
            val item = value as? JsonObject ?: return@forEachIndexed
            if (item.string("type") != "function_call") return@forEachIndexed
            val itemCallId = item.string("call_id")
            val existing = itemCallId?.let { id -> calls.values.firstOrNull { it.callId == id } }
            val call = existing ?: calls.getOrPut(index) { PendingCall() }
            call.callId = itemCallId ?: call.callId
            call.name = item.string("name") ?: call.name
            item.string("arguments")?.let { call.arguments.replace(0, call.arguments.length, it) }
        }
    }

    fun finish(): List<StreamDelta> {
        if (emittedCalls) return emptyList()
        emittedCalls = true
        val toolCalls = calls.toSortedMap().values
            .filter { it.callId.isNotBlank() && it.name.isNotBlank() }
            .map { ToolCallDelta(it.callId, it.name, it.arguments.toString().ifBlank { "{}" }) }
        return if (toolCalls.isEmpty()) emptyList() else listOf(StreamDelta(toolCalls = toolCalls))
    }

    fun collectedText(): String = content.toString()
}

internal fun parseResponsesApiBody(text: String): List<StreamDelta> {
    val response = runCatching { AppJson.json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw IllegalStateException("Invalid Responses API body", it) }
    response["error"]?.let { error ->
        val message = (error as? JsonObject)?.string("message") ?: error.toString()
        throw IllegalStateException(message.take(500))
    }
    val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
    val deltas = mutableListOf<StreamDelta>()
    val calls = mutableListOf<ToolCallDelta>()
    output.forEach { value ->
        val item = value as? JsonObject ?: return@forEach
        when (item.string("type")) {
            "message" -> {
                val blocks = item["content"] as? JsonArray ?: JsonArray(emptyList())
                blocks.forEach { blockValue ->
                    val block = blockValue as? JsonObject ?: return@forEach
                    when (block.string("type")) {
                        "output_text", "text" -> if (deltas.none { it.text.isNotEmpty() }) {
                            block.string("text")?.takeIf { it.isNotEmpty() }?.let { deltas += StreamDelta(text = it) }
                        }
                        "reasoning_text", "summary_text" -> block.string("text")?.takeIf { it.isNotEmpty() }
                            ?.let { deltas += StreamDelta(reasoning = it) }
                    }
                }
            }
            "reasoning" -> {
                val summaries = item["summary"] as? JsonArray ?: JsonArray(emptyList())
                summaries.forEach { value2 ->
                    val summary = value2 as? JsonObject ?: return@forEach
                    summary.string("text")?.takeIf { it.isNotEmpty() }?.let { deltas += StreamDelta(reasoning = it) }
                }
            }
            "function_call" -> {
                val callId = item.string("call_id")
                val name = item.string("name")
                if (!callId.isNullOrBlank() && !name.isNullOrBlank()) {
                    calls += ToolCallDelta(callId, name, item.string("arguments").orEmpty().ifBlank { "{}" })
                }
            }
        }
    }
    response["usage"]?.let { usageElement ->
        val usage = usageElement as? JsonObject
        if (usage != null) {
            val input = usage.int("input_tokens") ?: 0
            val outputTokens = usage.int("output_tokens") ?: 0
            deltas += StreamDelta(usage = TokenUsage(input, outputTokens, input + outputTokens))
        }
    }
    if (calls.isNotEmpty()) deltas += StreamDelta(toolCalls = calls)
    return deltas
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

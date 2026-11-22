/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.MessageSegment
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

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

    private enum class Kind { TEXT, REASONING, SUMMARY, IMAGE }
    private data class Block(val output: Int, val content: Int, val kind: Kind)
    private val blocks = linkedMapOf<Block, StringBuilder>()
    private val sent = mutableListOf<Block>()
    private var missingOrder = false
    private val itemIndices = mutableMapOf<String, Int>()
    private val calls = linkedMapOf<Int, PendingCall>()
    private var latestUsage: TokenUsage? = null
    private var emittedCalls = false
    private val imageItems = mutableSetOf<String>()
    var finalized = false
        private set

    fun accept(data: String): List<StreamDelta> {
        if (data.isBlank() || data == "[DONE]") return emptyList()
        val event = runCatching { AppJson.json.parseToJsonElement(data).jsonObject }
            .getOrElse { throw IllegalStateException("Invalid Responses API event", it) }
        if (finalized) return emptyList()
        val type = event.string("type").orEmpty()
        val itemId = event.string("item_id") ?: (event["item"] as? JsonObject)?.string("id")
        val outputIndex = event.int("output_index") ?: itemId?.let(itemIndices::get) ?: -1
        if (itemId != null && outputIndex >= 0) itemIndices[itemId] = outputIndex
        val emitted = mutableListOf<StreamDelta>()
        when (type) {
            "response.output_text.delta", "response.reasoning_text.delta", "response.reasoning_summary_text.delta",
            "response.output_text.done", "response.reasoning_text.done", "response.reasoning_summary_text.done" -> {
                val kind = when {
                    type.contains("summary") -> Kind.SUMMARY
                    type.contains("reasoning") -> Kind.REASONING
                    else -> Kind.TEXT
                }
                val index = event.int(if (kind == Kind.SUMMARY) "summary_index" else "content_index") ?: 0
                val block = Block(outputIndex.coerceAtLeast(0), index, kind)
                if (type.endsWith(".delta")) event.string("delta")?.let { appendBlock(block, it, emitted) }
                else event.string("text")?.let { completeBlock(block, it, emitted) }
            }
            "response.output_item.added", "response.output_item.done" -> {
                val item = event["item"] as? JsonObject
                if (type == "response.output_item.done" && item?.string("type") == "image_generation_call") {
                    emitImage(itemId, outputIndex, item.string("result"), emitted)
                }
                if (item?.string("type") == "function_call" && outputIndex >= 0) {
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
            "response.image_generation_call.completed" -> {
                emitImage(itemId, outputIndex, event.string("result"), emitted)
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
                    latestUsage = parseResponsesTokenUsage(usage)
                }
                (response?.get("output") as? JsonArray)?.let { items ->
                    readOutputItems(items)
                    items.forEachIndexed { index, value ->
                        val item = value as? JsonObject ?: return@forEachIndexed
                        val id = item.string("id")
                        val position = id?.let(itemIndices::get) ?: index
                        when (item.string("type")) {
                            "message" -> completeBlocks(position, Kind.TEXT, item["content"], emitted)
                            "reasoning" -> {
                                completeBlocks(position, Kind.REASONING, item["content"], emitted)
                                completeBlocks(position, Kind.SUMMARY, item["summary"], emitted)
                            }
                            "image_generation_call" -> emitImage(id, position, item.string("result"), emitted)
                        }
                    }
                }
                val ordered = blocks.keys.sortedWith(compareBy<Block>({ it.output }, { it.kind.ordinal }, { it.content }))
                if (missingOrder || sent != ordered) {
                    val snapshot = ordered.map { block ->
                        val text = blocks.getValue(block).toString()
                        if (block.kind == Kind.TEXT || block.kind == Kind.IMAGE) MessageSegment.Text(text) else MessageSegment.Reasoning(text)
                    }
                    emitted += StreamDelta(outputSnapshot = snapshot)
                }
                latestUsage?.let { emitted += StreamDelta(usage = it) }
                finalized = true
            }
            "response.failed", "error" -> {
                val error = event["error"] as? JsonObject
                val detail = error?.string("message") ?: event.string("message")
                    ?: event.toString()
                throw IllegalStateException("Responses API $type: ${detail.take(500)}")
            }
        }
        return emitted
    }


    private fun appendBlock(block: Block, text: String, output: MutableList<StreamDelta>) {
        if (text.isEmpty()) return
        val buffer = blocks.getOrPut(block) { StringBuilder() }
        if (buffer.isEmpty()) sent += block
        else if (sent.lastOrNull() != block) missingOrder = true
        buffer.append(text)
        output += if (block.kind == Kind.TEXT || block.kind == Kind.IMAGE) StreamDelta(text = text) else StreamDelta(reasoning = text)
    }

    private fun completeBlock(block: Block, text: String, output: MutableList<StreamDelta>) {
        val previous = blocks[block]
        when {
            previous == null -> appendBlock(block, text, output)
            text.startsWith(previous) -> appendBlock(block, text.substring(previous.length), output)
            text.isNotEmpty() -> {
                previous.replace(0, previous.length, text)
                missingOrder = true
            }
        }
    }

    private fun completeBlocks(index: Int, kind: Kind, value: JsonElement?, output: MutableList<StreamDelta>) {
        (value as? JsonArray).orEmpty().forEachIndexed { contentIndex, element ->
            val block = element as? JsonObject ?: return@forEachIndexed
            if (kind == Kind.TEXT && block.string("type") !in setOf("output_text", "text")) return@forEachIndexed
            block.string("text")?.let { completeBlock(Block(index, contentIndex, kind), it, output) }
        }
    }

    private fun emitImage(id: String?, index: Int, result: String?, output: MutableList<StreamDelta>) {
        if (result.isNullOrBlank()) return
        val digestKey = if (id == null || index < 0) "image:${result.length}:${result.hashCode()}" else null
        val idKey = id?.let { "id:$it" }
        val indexKey = index.takeIf { it >= 0 }?.let { "index:$it" }
        val duplicate = listOfNotNull(idKey, indexKey, digestKey).any { it in imageItems }
        imageItems.addAll(listOfNotNull(idKey, indexKey, digestKey))
        if (duplicate) return
        val position = index.takeIf { it >= 0 } ?: (Int.MAX_VALUE - 1024)
        val block = Block(position, if (index >= 0) 0 else blocks.size, Kind.IMAGE)
        appendBlock(block, imageResultMarkdown(result), output)
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

    fun collectedText(): String = blocks.keys.filter { it.kind == Kind.TEXT }
        .sortedWith(compareBy({ it.output }, { it.content })).joinToString("") { blocks.getValue(it) }
}

internal fun parseResponsesApiBody(text: String): List<StreamDelta> {
    val response = runCatching { AppJson.json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw IllegalStateException("Invalid Responses API body", it) }
    response["error"]?.takeUnless { it is JsonNull }?.let { error ->
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
                        "output_text", "text" -> block.string("text")?.takeIf { it.isNotEmpty() }
                            ?.let { deltas += StreamDelta(text = it) }
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
            "image_generation_call" -> item.string("result")?.takeIf { it.isNotBlank() }?.let {
                deltas += StreamDelta(text = imageResultMarkdown(it))
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
            deltas += StreamDelta(usage = parseResponsesTokenUsage(usage))
        }
    }
    if (calls.isNotEmpty()) deltas += StreamDelta(toolCalls = calls)
    return deltas
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun imageResultMarkdown(result: String): String {
    val value = result.trim()
    return if (value.startsWith("http://", true) ||
        value.startsWith("https://", true) ||
        value.startsWith("data:image/", true)) imageMarkdown(value)
    else imageMarkdown("data:image/png;base64,$value")
}

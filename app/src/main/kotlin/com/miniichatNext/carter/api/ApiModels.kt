package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Anthropic的多块content和OpenAI的多oart都用这个 */
@Serializable
sealed class ChatPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ChatPart()

    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        @SerialName("image_url") val imageUrl: ImageUrlData,
    ) : ChatPart() {
        @Serializable
        data class ImageUrlData(val url: String, val detail: String? = null)
    }

    /** 仅Anthropic时模型发起的tool_use块（输出在请求里回放时也用这个） */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ChatPart()

    /** 仅Anthropic时把工具结果回传给模型 */
    @Serializable
    @SerialName("tool_result")
    data class ToolResultPart(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : ChatPart()
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val parts: List<ChatPart>? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    fun isMultipart(): Boolean = !parts.isNullOrEmpty()

    companion object {
        fun toolResult(toolCallId: String, content: String) =
            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
    }
}

/** 一次工具调用（回放给模型时使用） */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 传给模型的工具描述；inputSchema是JSON Schema原文 */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: String,
)

data class StreamDelta(
    val text: String = "",
    val reasoning: String = "",
    val usage: TokenUsage? = null,
    /** 本轮流式末尾收集到的工具调用（每轮一个final delta） */
    val toolCalls: List<ToolCallDelta> = emptyList(),
)

data class ToolCallDelta(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
internal data class ChatChunk(
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null,
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        val role: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallChunk>? = null,
    )

    @Serializable
    data class ToolCallChunk(
        val index: Int,
        val id: String? = null,
        val type: String? = null,
        val function: FunctionCall? = null,
    )

    @Serializable
    data class FunctionCall(
        val name: String? = null,
        val arguments: String = "",
    )

    @Serializable
    data class ResponseMessage(
        val role: String = "assistant",
        val content: String = "",
        @SerialName("tool_calls") val toolCalls: List<ToolCallChunk>? = null,
    )

    @Serializable
    data class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
    )
}

@Serializable
internal data class ChatResponse(
    val choices: List<ChatChunk.Choice> = emptyList(),
    val usage: ChatChunk.UsageDto? = null,
)

@Serializable
internal data class ModelEntry(val id: String)

@Serializable
internal data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
internal data class ClaudeModelEntry(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
internal data class ClaudeModelsResponse(val data: List<ClaudeModelEntry> = emptyList())

/** OpenAI Chat Completions请求体 */
@Serializable
internal data class OpenAiRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    val max_tokens: Int? = null,
    val tools: List<OpenAiToolWrapper>? = null,
)

@Serializable
internal data class OpenAiToolWrapper(
    val type: String = "function",
    val function: OpenAiFunctionWrapper,
)

@Serializable
internal data class OpenAiFunctionWrapper(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
)

/** Anthropic Messages请求体 */
@Serializable
internal data class ClaudeRequest(
    val model: String,
    val system: String? = null,
    val messages: List<ChatMessage>,
    val max_tokens: Int,
    val stream: Boolean = false,
    val temperature: Float? = null,
    val tools: List<ClaudeToolWrapper>? = null,
)

@Serializable
internal data class ClaudeToolWrapper(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

/** Anthropic流式事件 */
@Serializable
internal data class ClaudeStreamEvent(
    val type: String = "",
    @SerialName("message") val message: ClaudeStreamMessage? = null,
    val index: Int? = null,
    @SerialName("content_block") val contentBlock: ClaudeContentBlock? = null,
    val delta: ClaudeDelta? = null,
    @SerialName("content_block_start") val contentBlockStart: ClaudeContentBlockStart? = null,
    @SerialName("content_block_delta") val contentBlockDelta: ClaudeContentBlockDelta? = null,
    @SerialName("message_delta") val messageDelta: ClaudeMessageDelta? = null,
    val usage: ClaudeUsage? = null,
) {
    @Serializable
    data class ClaudeStreamMessage(
        val id: String? = null,
        val role: String? = null,
        val model: String? = null,
        val usage: ClaudeUsage? = null,
    )

    @Serializable
    data class ClaudeContentBlock(val type: String = "")

    @Serializable
    data class ClaudeDelta(
        val type: String? = null,
        val text: String? = null,
        @SerialName("thinking") val thinking: String? = null,
    )

    @Serializable
    data class ClaudeContentBlockStart(
        val type: String? = null,
        val id: String? = null,
        val name: String? = null,
        val index: Int? = null,
    )

    @Serializable
    data class ClaudeContentBlockDelta(
        val type: String? = null,
        val index: Int? = null,
        @SerialName("partial_json") val partialJson: String? = null,
    )

    @Serializable
    data class ClaudeMessageDelta(
        @SerialName("stop_reason") val stopReason: String? = null,
        val stop_details: ClaudeStopDetails? = null,
    )

    @Serializable
    data class ClaudeStopDetails(val type: String? = null)

    @Serializable
    data class ClaudeUsage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )
}
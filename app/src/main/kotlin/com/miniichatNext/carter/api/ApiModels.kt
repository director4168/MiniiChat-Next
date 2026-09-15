package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ChatPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
) {
    @Serializable
    data class ImageUrl(val url: String, val detail: String? = null)
}

data class ChatMessage(
    val role: String,
    val content: String,
    val parts: List<ChatPart>? = null
) {
    fun isMultipart(): Boolean = !parts.isNullOrEmpty()
}

data class StreamDelta(
    val text: String = "",
    val reasoning: String = "",
    val usage: TokenUsage? = null
)

@Serializable
internal data class ChatChunk(
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        val role: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null
    )

    @Serializable
    data class ResponseMessage(val role: String = "assistant", val content: String = "")

    @Serializable
    data class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0
    )
}

@Serializable
internal data class ChatResponse(
    val choices: List<ChatChunk.Choice> = emptyList(),
    val usage: ChatChunk.UsageDto? = null
)

@Serializable
internal data class ModelEntry(val id: String)

@Serializable
internal data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
internal data class ClaudeModelEntry(
    val id: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
internal data class ClaudeModelsResponse(val data: List<ClaudeModelEntry> = emptyList())

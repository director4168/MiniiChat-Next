package com.miniichatNext.carter.data

import kotlinx.serialization.Serializable

enum class Role { user, assistant, system }

@Serializable
data class Attachment(
    val type: String,
    val uri: String,
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0
)

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens
    )
}

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val usage: TokenUsage? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val assistantId: String = "default",
    val messages: List<Message> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val suggestionSeed: Int = 0,
    val suggestionPages: Map<Int, List<String>> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

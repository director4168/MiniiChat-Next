package com.miniichatNext.carter.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Assistant(
    val id: String,
    val name: String,
    val avatar: String = "🤖",
    val avatarPath: String? = null,
    val systemPrompt: String = "You are a helpful assistant.",
    val preferredProviderId: String? = null,
    val preferredModel: String? = null,
    val temperature: Float? = null,
    // 背景三种模式：default不设背景；image自定义图片（用backgroundPath）；css自定义CSS（用backgroundCss，由ChatScreen用WebView渲染）
    val backgroundMode: String = "default",
    val backgroundPath: String? = null,
    val backgroundCss: String = "",
    val backgroundOpacity: Float = 1f,
    val enabledSkillIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val hasAvatarImage: Boolean get() = !avatarPath.isNullOrBlank()
    // 向后兼容：旧数据里没有backgroundMode字段（反序列化默认为default）
    // 但backgroundPath已存在，这种情况下仍按图片模式判定hasBackground=true
    val hasBackground: Boolean
        get() = if (backgroundMode == "css") backgroundCss.isNotBlank()
                else !backgroundPath.isNullOrBlank()
}

object AssistantPresets {
    fun defaults(): List<Assistant> = listOf(
        Assistant(
            id = "default",
            name = "Default",
            avatar = "🤖",
            systemPrompt = "You are a helpful assistant. Today is {date}. The user is talking to you via {model}."
        ),
        Assistant(
            id = "coder",
            name = "Coder",
            avatar = "💻",
            systemPrompt = "You are an expert programmer. Answer with concise, correct code. " +
                "Prefer modern idiomatic style. When showing code, always use fenced code blocks with the language tag. " +
                "Today is {date}."
        ),
        Assistant(
            id = "translator",
            name = "Translator",
            avatar = "🌐",
            systemPrompt = "You are a professional translator. Translate the user's input between English and Chinese. " +
                "Preserve tone, formatting and technical terms. If the input is mixed, translate every sentence to the other language."
        ),
        Assistant(
            id = "writer",
            name = "Writer",
            avatar = "✍️",
            systemPrompt = "You are a writing partner. Help the user write clear, engaging prose. " +
                "Suggest edits, rephrase, brainstorm ideas. Keep responses tight unless asked for longer drafts."
        )
    )
}

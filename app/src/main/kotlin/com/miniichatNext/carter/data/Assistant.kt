package com.miniichatNext.carter.data

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
    // 背景三种模式：default = 不设背景；image = 自定义图片（用 backgroundPath）；
    // css = 自定义 CSS（用 backgroundCss，由 ChatScreen 用 WebView 渲染）
    val backgroundMode: String = "default",
    val backgroundPath: String? = null,
    val backgroundCss: String = "",
    val backgroundOpacity: Float = 1f,
    val enabledSkillIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val hasAvatarImage: Boolean get() = !avatarPath.isNullOrBlank()
    // 向后兼容：旧数据里没有 backgroundMode 字段（反序列化默认为 "default"）
    // 但 backgroundPath 已存在，这种情况下仍按"图片模式"判定 hasBackground=true
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

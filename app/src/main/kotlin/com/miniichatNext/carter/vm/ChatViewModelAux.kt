package com.miniichatNext.carter.vm

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal fun ChatViewModel.resolveModelRef(ref: String): Pair<ProviderConfig, String>? {
    if (ref.isBlank()) return null
    if (ref.contains("::")) {
        val pid = ref.substringBefore("::")
        val mid = ref.substringAfter("::")
        val provider = providers.value.firstOrNull { it.id == pid } ?: return null
        if (mid.isBlank()) return null
        return provider to mid
    }
    val provider = activeProvider() ?: return null
    return provider to ref
}

/**
 * 使用辅助功能模型运行一次性（非流式）补全
 * 如果未配置功能模型，则返回null，以便调用者可以提示用户在设置中进行配置
 */
internal suspend fun ChatViewModel.runAuxCompletion(
    modelRef: String,
    prompt: String
): String? {
    val (provider, model) = resolveModelRef(modelRef) ?: return null
    if (provider.apiKey.isBlank()
        && !provider.baseUrl.contains("localhost")
        && !provider.baseUrl.contains("10.0.2.2")
    ) {
        return null
    }
    val current = settings.value
    val assistant = activeAssistant()
    val effectiveSettings = current.copy(
        stream = false,
        temperature = assistant?.temperature ?: current.temperature
    )
    val sb = StringBuilder()
    return try {
        client.chatStream(
            provider = provider,
            settings = effectiveSettings,
            modelId = model,
            messages = listOf(ChatMessage("user", prompt))
        ).collect { delta -> if (delta.text.isNotEmpty()) sb.append(delta.text) }
        sb.toString().trim().ifBlank { null }
    } catch (e: Exception) {
        null
    }
}

/** 完成一次兑换后，根据第一条对话生成标题 */
internal fun ChatViewModel.generateTitle() {
    val convId = _activeId.value ?: return
    val conv = conversations.value.firstOrNull { it.id == convId } ?: return
    if (conv.messages.isEmpty()) return
    if (conv.messages.size > 2) return
    val placeholder = conv.messages.firstOrNull { it.role == "user" }
        ?.content?.take(30)?.replace("\n", " ")
    if (conv.title.isNotBlank() && conv.title != placeholder) return
    val ref = settings.value.titleModel
    if (ref.isBlank()) {
        _toast.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_title)
        return
    }
    viewModelScope.launch {
        val content = conv.messages.take(4).joinToString("\n\n") { m ->
            "${m.role}: ${m.content.take(500)}"
        }
        val locale = java.util.Locale.getDefault().displayName
        val prompt = buildString {
            append("Summarize the following conversation into a short title.\n")
            append("Reply with the title only, no punctuation, max 10 characters.\n")
            append("Use this language: ").append(locale).append("\n\n")
            append(content)
        }
        val title = runAuxCompletion(ref, prompt)
        if (title.isNullOrBlank()) {
            _toast.value = getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_generation_failed)
        } else {
            store.rename(convId, title)
        }
    }
}

/** 回复候选词 */
internal fun ChatViewModel.generateSuggestions(page: Int) {
    val convId = _activeId.value ?: return
    val conv = conversations.value.firstOrNull { it.id == convId } ?: return
    if (conv.messages.isEmpty()) return
    val assistant = activeAssistant()
    val activeProvider = activeProvider()
    val activeModel = settings.value.activeModel
    if (activeProvider == null || activeModel.isBlank()) {
        _toast.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_suggestion)
        return
    }
    val provider = assistant?.preferredProviderId
        ?.let { id -> providers.value.firstOrNull { it.id == id } }
        ?: activeProvider
    val model = assistant?.preferredModel?.takeIf { it.isNotBlank() } ?: activeModel
    _suggestionsGenerating.value = _suggestionsGenerating.value + page
    viewModelScope.launch {
        try {
        val content = conv.messages.takeLast(8).joinToString("\n\n") { m ->
            "${m.role}: ${m.content.take(500)}"
        }
        val prompt = buildString {
            append("I will give you some chat content. Act as the User and give exactly 4 ")
            append("appropriate reply suggestions, separated by newlines.\n")
            append("Keep each suggestion very short (about 10 characters in Chinese, ")
            append("or under 6 words in English).\n")
            append("Imitate the user's style. Do not add formatting or list markers.\n")
            append("Reply in the SAME language as the chat content above ")
            append("(if the chat is in English, reply in English; never switch language).\n")
            append("Seed for variety: page ").append(page).append("\n\n")
            append(content)
        }
        val settings = settings.value
        val temperature = assistant?.temperature ?: settings.temperature
        val sb = StringBuilder()
        val text = try {
            client.chatStream(
                provider = provider,
                settings = settings.copy(stream = false, temperature = temperature),
                modelId = model,
                messages = listOf(ChatMessage("user", prompt))
            ).collect { delta -> if (delta.text.isNotEmpty()) sb.append(delta.text) }
            sb.toString().trim()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            _toast.value = getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_generation_failed)
            return@launch
        }
        val suggestions = text.split("\n")
            .map { it.trim().trimStart('-', '1', '2', '3', '4', '.', ' ').trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
        if (suggestions.isEmpty()) return@launch
        val latest = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
        val pages = latest.suggestionPages + (page to suggestions)
        store.upsert(
            latest.copy(
                chatSuggestions = suggestions,
                suggestionSeed = page,
                suggestionPages = pages,
                updatedAt = System.currentTimeMillis()
            )
        )
        } finally {
            // 无论成功/失败/提前return，都要清掉生成中标记，否则骨架屏会一直转
            _suggestionsGenerating.value = _suggestionsGenerating.value - page
        }
    }
}

/** 对话压缩 */
internal fun ChatViewModel.compressContext(targetTokens: Int, keepRecentMessages: Int, additionalPrompt: String) {
    val convId = _activeId.value ?: return
    val conv = conversations.value.firstOrNull { it.id == convId } ?: return
    val ref = settings.value.compressModel
    if (ref.isBlank()) {
        _toast.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_compress)
        return
    }
    viewModelScope.launch {
        val all = conv.messages
        val messagesToCompress: List<Message>
        val messagesToKeep: List<Message>
        if (keepRecentMessages > 0 && all.size > keepRecentMessages) {
            messagesToCompress = all.dropLast(keepRecentMessages)
            messagesToKeep = all.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            _toast.value = getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.compress_not_enough)
            return@launch
        } else {
            messagesToCompress = all
            messagesToKeep = emptyList()
        }

        val content = messagesToCompress.joinToString("\n\n") { m ->
            "${m.role}: ${m.content.take(2000)}"
        }
        val locale = java.util.Locale.getDefault().displayName
        val prompt = buildString {
            append("You are a conversation compression assistant. Compress the following ")
            append("conversation into a concise summary.\n")
            append("Preserve key facts, decisions and context.\n")
            append("Target approximately ").append(targetTokens).append(" tokens.\n")
            append("Output the summary directly without meta-commentary.\n")
            append("Use this language: ").append(locale).append("\n")
            if (additionalPrompt.isNotBlank()) {
                append("Additional instructions: ").append(additionalPrompt).append("\n")
            }
            append("\n<conversation>\n").append(content).append("\n</conversation>")
        }
        val summary = runAuxCompletion(ref, prompt) ?: run {
            _toast.value = getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_generation_failed)
            return@launch
        }
        val latest = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
        val summaryMsg = Message(
            id = newId(),
            role = "user",
            content = "[Summary of previous conversation]\n$summary"
        )
        store.upsert(
            latest.copy(
                messages = listOf(summaryMsg) + messagesToKeep,
                chatSuggestions = emptyList(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

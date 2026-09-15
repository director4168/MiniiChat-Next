package com.miniichatNext.carter.vm

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.util.PromptVars
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import com.miniichatNext.carter.api.ChatPart
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AttachmentLoader

internal fun ChatViewModel.sendMessage(text: String, attachments: List<com.miniichatNext.carter.data.model.Attachment> = emptyList()) {
    if (_isStreaming.value || streamingJob?.isActive == true) return
    val trimmed = text.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return

    val current = settings.value
    val assistant = activeAssistant()

    val provider = assistant?.preferredProviderId
        ?.let { id -> providers.value.firstOrNull { it.id == id } }
        ?: activeProvider()
    if (provider == null) {
        _error.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.error_no_provider)
        return
    }

    val model = assistant?.preferredModel?.takeIf { it.isNotBlank() }
        ?: current.activeModel
    if (model.isBlank()) {
        _error.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.error_no_model)
        return
    }

    if (provider.apiKey.isBlank()
        && !provider.baseUrl.contains("localhost")
        && !provider.baseUrl.contains("10.0.2.2")
    ) {
        _error.value = getApplication<Application>()
            .getString(
                com.miniichatNext.carter.R.string.error_api_key_empty,
                provider.name
            )
        return
    }

    val temperature = assistant?.temperature ?: current.temperature
    val activeAssistantId = current.activeAssistantId

    com.miniichatNext.carter.debug.DebugLog.i(
        "ChatVM",
        "sendMessage chars=${trimmed.length} atts=${attachments.size} " +
            "provider=${provider.name} model=$model stream=${current.stream} " +
            "assistant=$activeAssistantId temp=$temperature"
    )

    viewModelScope.launch {
        val activeId = _activeId.value ?: newId().also { _activeId.value = it }
        val enabledSkills = activeEnabledSkills(assistant)
        com.miniichatNext.carter.debug.DebugLog.i(
            "ChatVM",
            "sendMessage skills=${enabledSkills.joinToString { it.name }} " +
                "convId=$activeId assistant=${assistant?.id ?: "null"}"
        )
        val skillsBlock = if (enabledSkills.isEmpty()) "" else buildString {
            append("\n\n# Active Skills\n")
            enabledSkills.forEach { s ->
                append("## ").append(s.name).append('\n')
                if (s.description.isNotBlank()) append("_").append(s.description).append("_\n")
                append(s.body.trim()).append("\n\n")
            }
        }
        val systemPromptRaw = assistant?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: current.systemPrompt
        val systemPrompt = PromptVars.render(
            template = systemPromptRaw + skillsBlock,
            model = model,
            provider = provider.name,
            assistant = assistant?.name ?: ""
        )

        val existing = store.snapshot().firstOrNull { it.id == activeId && it.assistantId == activeAssistantId }
        val baseTitle = trimmed.take(30).replace("\n", " ")

        val userMsg = Message(
            id = newId(),
            role = "user",
            content = trimmed,
            attachments = attachments
        )
        val assistantId = newId()
        val assistantPlaceholder = Message(id = assistantId, role = "assistant", content = "")

        val updated = (existing ?: Conversation(
            id = activeId,
            title = baseTitle,
            assistantId = activeAssistantId
        ))
            .let { conv ->
                conv.copy(
                    title = if (conv.messages.isEmpty()) baseTitle else conv.title,
                    messages = conv.messages + userMsg + assistantPlaceholder,
                    updatedAt = System.currentTimeMillis()
                )
            }
        store.upsert(updated)

        val historyForApi = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            historyForApi.add(ChatMessage("system", systemPrompt))
        }
        updated.messages
            .filter { !(it.role == "assistant" && it.content.isEmpty()) }
            .forEach { msg ->
                val imgs = msg.attachments.filter { it.type == "image" }
                if (msg.role == "user" && imgs.isNotEmpty()) {
                    val parts = mutableListOf<com.miniichatNext.carter.api.ChatPart>()
                    if (msg.content.isNotBlank()) {
                        parts.add(com.miniichatNext.carter.api.ChatPart(type = "text", text = msg.content))
                    }
                    for (att in imgs) {
                        val loaded = runCatching {
                            com.miniichatNext.carter.util.AttachmentLoader.load(
                                resolver = getApplication<android.app.Application>().contentResolver,
                                uri = android.net.Uri.parse(att.uri),
                                mimeFallback = att.mimeType.ifBlank { "image/jpeg" }
                            )
                        }.getOrNull() ?: continue
                        val dataUrl = "data:${loaded.mimeType};base64,${loaded.base64}"
                        parts.add(com.miniichatNext.carter.api.ChatPart(
                            type = "image_url",
                            imageUrl = com.miniichatNext.carter.api.ChatPart.ImageUrl(url = dataUrl)
                        ))
                    }
                    val others = msg.attachments.filter { it.type != "image" }
                    if (others.isNotEmpty()) {
                        val tail = others.joinToString("\n") { "[file: ${it.name} (${it.mimeType})]" }
                        val merged = if (parts.firstOrNull()?.type == "text") {
                            parts[0] = com.miniichatNext.carter.api.ChatPart(
                                type = "text",
                                text = (parts[0].text ?: "") + "\n\n" + tail
                            )
                            parts
                        } else {
                            listOf(com.miniichatNext.carter.api.ChatPart(type = "text", text = tail)) + parts
                        }
                        historyForApi.add(ChatMessage(msg.role, msg.content, merged))
                    } else {
                        historyForApi.add(ChatMessage(msg.role, msg.content, parts))
                    }
                } else if (msg.role == "user" && msg.attachments.isNotEmpty()) {
                    val refs = msg.attachments.joinToString("\n") {
                        "[file: ${it.name} (${it.mimeType})]"
                    }
                    val combined = if (msg.content.isBlank()) refs else "${msg.content}\n\n$refs"
                    historyForApi.add(ChatMessage(msg.role, combined))
                } else {
                    historyForApi.add(ChatMessage(msg.role, msg.content))
                }
            }

        _isStreaming.value = true
        val builder = StringBuilder()
        val effectiveSettings = current.copy(temperature = temperature)
        var lastUsage: TokenUsage? = null

        streamingJob = launch {
            var lastFlush = 0L
            val flushIntervalMs = 800L
            var lastPersistedLength = 0
            _streamingOverlay.value = assistantId to ""
            try {
                client.chatStream(provider, effectiveSettings, model, historyForApi)
                    .catch { e ->
                        com.miniichatNext.carter.debug.DebugLog.e(
                            "ChatVM", "stream error (regenerate path)", e
                        )
                        _error.value = e.message ?: "Request failed"
                        val finalContent = if (builder.isEmpty()) "(error: ${e.message})" else builder.toString()
                        appendAssistant(activeId, assistantId, finalContent, lastUsage)
                    }
                    .collect { delta ->
                        if (delta.text.isNotEmpty()) builder.append(delta.text)
                        if (delta.usage != null) lastUsage = delta.usage
                        _streamingOverlay.value = assistantId to builder.toString()
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= flushIntervalMs
                            && builder.length - lastPersistedLength >= 40) {
                            appendAssistant(activeId, assistantId, builder.toString(), lastUsage)
                            lastFlush = now
                            lastPersistedLength = builder.length
                        }
                    }
            } finally {
                if (builder.isNotEmpty()) {
                    appendAssistant(activeId, assistantId, builder.toString(), lastUsage)
                }
                lastUsage?.let { settingsRepo.addTokenUsage(it) }
                _streamingOverlay.value = null
                _isStreaming.value = false
                streamingJob = null
                // 再完成一次对话后自动生成标题，不过需要用户已配置标题生成模型
                generateTitle()
            }
        }
    }
}

internal suspend fun ChatViewModel.appendAssistant(convId: String, msgId: String, content: String, usage: TokenUsage?) {
    store.updateMessages(convId) { msgs ->
        msgs.map {
            if (it.id == msgId) it.copy(content = content, usage = usage ?: it.usage) else it
        }
    }
}

internal fun ChatViewModel.regenerate() {
    viewModelScope.launch {
        val convId = _activeId.value ?: return@launch
        val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
        val msgs = conv.messages
        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return@launch
        val lastUser = msgs[lastUserIdx]
        val trimmed = msgs.subList(0, lastUserIdx + 1)
        store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
        sendMessage(lastUser.content, lastUser.attachments)
    }
}

internal fun ChatViewModel.continueGenerating() {
    viewModelScope.launch {
        val convId = _activeId.value ?: return@launch
        val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
        val msgs = conv.messages
        if (msgs.isEmpty()) return@launch
        val last = msgs.last()
        if (last.role != "assistant" || last.content.isEmpty()) return@launch

        val current = settings.value
        val assistant = activeAssistant()
        val provider = assistant?.preferredProviderId
            ?.let { id -> providers.value.firstOrNull { it.id == id } }
            ?: activeProvider()
            ?: run {
                _error.value = getApplication<Application>()
                    .getString(com.miniichatNext.carter.R.string.error_no_provider)
                return@launch
            }
        val model = assistant?.preferredModel?.takeIf { it.isNotBlank() }
            ?: current.activeModel
        if (model.isBlank()) {
            _error.value = getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.error_no_model)
            return@launch
        }

        val temperature = assistant?.temperature ?: current.temperature
        val systemPrompt = PromptVars.render(
            template = assistant?.systemPrompt?.takeIf { it.isNotBlank() } ?: current.systemPrompt,
            model = model, provider = provider.name, assistant = assistant?.name ?: ""
        )

        val historyForApi = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) historyForApi.add(ChatMessage("system", systemPrompt))
        msgs.filter { !(it.role == "assistant" && it.content.isEmpty()) }
            .forEach { historyForApi.add(ChatMessage(it.role, it.content)) }
        historyForApi.add(ChatMessage("user", "Continue exactly where you left off."))

        val assistantId = newId()
        val updated = conv.copy(
            messages = conv.messages + Message(id = assistantId, role = "assistant", content = ""),
            updatedAt = System.currentTimeMillis()
        )
        store.upsert(updated)

        _isStreaming.value = true
        val builder = StringBuilder()
        val effectiveSettings = current.copy(temperature = temperature)
        var lastUsage: TokenUsage? = null

        streamingJob = launch {
            _streamingOverlay.value = assistantId to ""
            try {
                client.chatStream(provider, effectiveSettings, model, historyForApi)
                    .catch { e ->
                        com.miniichatNext.carter.debug.DebugLog.e(
                            "ChatVM", "stream error (send path)", e
                        )
                        _error.value = e.message ?: "Request failed"
                        val finalContent = if (builder.isEmpty()) "(error: ${e.message})" else builder.toString()
                        appendAssistant(convId, assistantId, finalContent, lastUsage)
                    }
                    .collect { delta ->
                        if (delta.text.isNotEmpty()) builder.append(delta.text)
                        if (delta.usage != null) lastUsage = delta.usage
                        _streamingOverlay.value = assistantId to builder.toString()
                    }
            } finally {
                if (builder.isNotEmpty()) {
                    appendAssistant(convId, assistantId, builder.toString(), lastUsage)
                }
                lastUsage?.let { settingsRepo.addTokenUsage(it) }
                _streamingOverlay.value = null
                _isStreaming.value = false
                streamingJob = null
            }
        }
    }
}

internal fun ChatViewModel.regenerateFrom(messageId: String) {
    viewModelScope.launch {
        val convId = _activeId.value ?: return@launch
        val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
        val msgs = conv.messages
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return@launch
        val target = msgs[idx]
        // 目标可以是用户消息本身，也可以是助手消息（此时回退到它前面那条用户消息）
        val anchorIdx = if (target.role == "user") {
            idx
        } else {
            msgs.subList(0, idx).indexOfLast { it.role == "user" }
        }
        if (anchorIdx < 0) return@launch
        val anchor = msgs[anchorIdx]
        // 从anchor处整体截断（连它一起），再重新发送一次
        store.upsert(
            conv.copy(messages = msgs.subList(0, anchorIdx), updatedAt = System.currentTimeMillis())
        )
        sendMessage(anchor.content, anchor.attachments)
    }
}

internal fun ChatViewModel.deleteMessage(messageId: String) {
    viewModelScope.launch {
        val convId = _activeId.value ?: return@launch
        store.updateMessages(convId) { it.filterNot { m -> m.id == messageId } }
    }
}

internal fun ChatViewModel.editMessage(messageId: String, newContent: String) {
    viewModelScope.launch {
        val convId = _activeId.value ?: return@launch
        store.updateMessages(convId) { msgs ->
            msgs.map { if (it.id == messageId) it.copy(content = newContent) else it }
        }
    }
}

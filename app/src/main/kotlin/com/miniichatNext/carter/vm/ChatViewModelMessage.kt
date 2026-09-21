package com.miniichatNext.carter.vm

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.ToolCall
import com.miniichatNext.carter.api.ToolSpec
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.model.ToolInvocationState
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.data.workspace.executeWorkspaceTool
import com.miniichatNext.carter.data.workspace.isWorkspaceTool
import com.miniichatNext.carter.data.workspace.resolveWorkspaceToolApproval
import com.miniichatNext.carter.data.workspace.workspaceToolSpecs
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AttachmentLoader
import com.miniichatNext.carter.util.InlineAttachment
import com.miniichatNext.carter.util.PromptVars
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal fun ChatViewModel.sendMessage(
    text: String,
    attachments: List<Attachment> = emptyList()
) {
    if (_isStreaming.value || streamingJob?.isActive == true) return
    val trimmed = text.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return

    val app = getApplication<Application>()
    val current = settings.value
    val assistant = activeAssistant()

    val provider = assistant?.preferredProviderId
        ?.let { id -> providers.value.firstOrNull { it.id == id } }
        ?: activeProvider()
    if (provider == null) {
        _error.value = app.getString(com.miniichatNext.carter.R.string.error_no_provider)
        return
    }

    val model = assistant?.preferredModel?.takeIf { it.isNotBlank() } ?: current.activeModel
    if (model.isBlank()) {
        _error.value = app.getString(com.miniichatNext.carter.R.string.error_no_model)
        return
    }

    if (provider.apiKey.isBlank()
        && !provider.baseUrl.contains("localhost")
        && !provider.baseUrl.contains("10.0.2.2")
    ) {
        _error.value = app.getString(
            com.miniichatNext.carter.R.string.error_api_key_empty,
            provider.name
        )
        return
    }

    val activeAssistantId = current.activeAssistantId
    viewModelScope.launch {
        val activeId = _activeId.value ?: newId().also { _activeId.value = it }
        val existing = store.snapshot().firstOrNull { it.id == activeId && it.assistantId == activeAssistantId }
        val baseTitle = trimmed.take(30).replace("\n", " ")

        val userMsg = Message(id = newId(), role = "user", content = trimmed, attachments = attachments)
        val conv = (existing ?: Conversation(id = activeId, title = baseTitle, assistantId = activeAssistantId))
            .let { c ->
                c.copy(
                    title = if (c.messages.isEmpty()) baseTitle else c.title,
                    messages = c.messages + userMsg,
                    updatedAt = System.currentTimeMillis()
                )
            }
        store.upsert(conv)

        DebugLog.i(
            "ChatVM",
            "send chars=${trimmed.length} atts=${attachments.size} provider=${provider.name} " +
                "model=$model assistant=$activeAssistantId"
        )
        triggerGeneration(activeId)
    }
}

/** 从持久化消息重建请求历史并发起流式生成；自动放行的工具会串行执行后继续生成 */
/**
 * 跑完一整轮生成→工具→生成…
 *
 * [reuseMsgId] 不为空时会续写那一条助手消息（工具调用后继续生成属于同一轮回复），而不是每条消息开一个新气泡
 */
internal fun ChatViewModel.triggerGeneration(cid: String, reuseMsgId: String? = null) {
    streamingJob?.cancel()
    streamingJob = viewModelScope.launch {
        _isStreaming.value = true
        _streamingOverlay.value = null
        try {
            var round = 0
            var reuse = reuseMsgId
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val res = runGenerationRound(cid, reuse) ?: return@launch
                reuse = res.msgId
                if (res.pending.isEmpty()) break
                if (!executeAutoApprovedTools(cid, res.pending)) break
            }
        } finally {
            _streamingOverlay.value = null
            _isStreaming.value = false
            streamingJob = null
        }
    }
}

private const val MAX_TOOL_ROUNDS = 8

/** 跑一轮流式生成并落库，返回本轮待处理的工具调用；返回null表示无法继续 */
private data class RoundResult(val msgId: String, val pending: List<ToolInvocation>)

private suspend fun ChatViewModel.runGenerationRound(cid: String, reuseMsgId: String?): RoundResult? {
    val conv = store.snapshot().firstOrNull { it.id == cid } ?: return null
    val assistant = assistantForConversation(conv)
    val provider = assistant?.preferredProviderId
        ?.let { id -> providers.value.firstOrNull { it.id == id } }
        ?: activeProvider() ?: run {
        _error.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.error_no_provider)
        return null
    }
    val model = assistant?.preferredModel?.takeIf { it.isNotBlank() }
        ?: settings.value.activeModel
    if (model.isBlank()) {
        _error.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.error_no_model)
        return null
    }

    val apiMessages = buildApiMessages(conv, assistant, provider, model)
    if (apiMessages.none { it.role == "user" || it.role == "tool" }) return null

    val tools = collectToolSpecs(conv, assistant)
    // 续写：复用上一轮那条助手消息，不再新开气泡
    val existing = reuseMsgId?.let { id -> conv.messages.firstOrNull { it.id == id } }
    val assistantMsg = existing ?: Message(
        id = newId(),
        role = "assistant",
        content = "",
        providerId = provider.id,
        providerName = provider.name,
        model = model
    )
    if (existing == null) store.updateMessages(cid) { it + assistantMsg }

    // 本轮新增的部分（用于流式覆盖层）；落库时和旧内容拼接
    val priorContent = existing?.content.orEmpty()
    val priorReasoning = existing?.reasoningContent.orEmpty()

    val current = settings.value
    val temperature = assistant?.temperature ?: current.temperature
    val effectiveSettings = current.copy(temperature = temperature)
    val startTime = SystemClock.elapsedRealtime()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    // 必须带上之前几轮的调用，否则落库时会把它们覆盖掉
    val invocations = (existing?.toolInvocations ?: emptyList()).toMutableList()
    var usage: TokenUsage? = null

    fun mergedContent(): String = when {
        priorContent.isBlank() -> text.toString()
        text.isBlank() -> priorContent
        else -> priorContent + "\n\n" + text
    }

    fun mergedReasoning(): String = listOf(priorReasoning, reasoning.toString())
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

    client.chatStream(provider, effectiveSettings, model, apiMessages, tools,
        override = conv.providerOverride(provider.id),
        thinking = conv.effectiveThinking())
        .catch { e ->
            DebugLog.e("ChatVM", "stream error", e)
            _error.value = e.message ?: "Request failed"
            if (text.isEmpty() && invocations.isEmpty()) {
                text.append("(error: ${e.message ?: "unknown"})")
            }
        }
        .collect { delta ->
            if (delta.text.isNotEmpty()) {
                text.append(delta.text)
                _streamingOverlay.value = assistantMsg.id to mergedContent()
            }
            if (delta.reasoning.isNotEmpty()) reasoning.append(delta.reasoning)
            delta.toolCalls.forEach { tc ->
                val idx = invocations.indexOfFirst { it.callId == tc.id }
                if (idx >= 0) {
                    invocations[idx] = invocations[idx].copy(arguments = tc.argumentsJson)
                } else {
                    invocations.add(
                        ToolInvocation(
                            callId = tc.id,
                            toolName = tc.name,
                            arguments = tc.argumentsJson,
                            state = ToolInvocationState.PENDING_APPROVAL
                        )
                    )
                }
            }

            val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)
            usage = delta.usage ?: TokenUsage(
                completionTokens = (text.length / 2).coerceAtLeast(1),
                totalTokens = (text.length / 2).coerceAtLeast(1),
                durationMs = elapsed,
                tokensPerSecond = ((text.length / 2).coerceAtLeast(1) * 1000f) / elapsed
            )
            persistAssistantStep(cid, assistantMsg.id, mergedContent(), mergedReasoning(), invocations, usage)
        }

    if (text.isBlank() && invocations.isEmpty()) {
        // 只在首轮且完全没产出时才写占位符，续写时不要覆盖掉之前的内容
        if (priorContent.isBlank()) {
            val fallback = if (reasoning.isNotBlank()) "（已完成思考，未返回文本）" else "（模型未返回内容）"
            persistAssistantStep(cid, assistantMsg.id, fallback, mergedReasoning(), invocations, usage)
        }
    } else {
        persistAssistantStep(cid, assistantMsg.id, mergedContent(), mergedReasoning(), invocations, usage)
    }

    usage?.let { settingsRepo.addTokenUsage(it) }
    generateTitle()
    return RoundResult(assistantMsg.id, invocations)
}

private suspend fun ChatViewModel.persistAssistantStep(
    cid: String,
    msgId: String,
    content: String,
    reasoning: String,
    invocations: List<ToolInvocation>,
    usage: TokenUsage?
) {
    store.updateMessages(cid) { msgs ->
        msgs.map { m ->
            if (m.id == msgId) m.copy(
                content = content,
                reasoningContent = reasoning.ifBlank { null },
                toolInvocations = invocations.toList(),
                usage = usage ?: m.usage
            ) else m
        }
    }
    _streamingOverlay.value = msgId to content
}

internal fun ChatViewModel.approveToolCall(inv: ToolInvocation, customResult: String? = null) {
    val cid = _activeId.value ?: return
    approveToolCall(cid, inv, customResult)
}

internal fun ChatViewModel.rejectToolCall(inv: ToolInvocation) {
    val cid = _activeId.value ?: return
    rejectToolCall(cid, inv)
}

internal fun ChatViewModel.approveToolCall(cid: String, inv: ToolInvocation, customResult: String? = null) {
    viewModelScope.launch {
        updateToolInvocationState(cid, inv.callId, ToolInvocationState.RUNNING)
        val result = if (!customResult.isNullOrBlank()) {
            true to customResult
        } else {
            executeTool(cid, inv.toolName, inv.arguments)
        }
        updateToolInvocationState(
            cid,
            inv.callId,
            if (result.first) ToolInvocationState.SUCCESS else ToolInvocationState.ERROR,
            result.second
        )
        continueIfAllToolsAnswered(cid, inv.callId)
    }
}

internal fun ChatViewModel.rejectToolCall(cid: String, inv: ToolInvocation) {
    viewModelScope.launch {
        updateToolInvocationState(cid, inv.callId, ToolInvocationState.REJECTED, "User rejected this tool call.")
        continueIfAllToolsAnswered(cid, inv.callId)
    }
}

/**
 * 同一条助手消息里可能一次返回多个工具调用（有的自动放行、有的要审批）
 * OpenAI/Anthropic都要求assistant.tool_calls后面跟着每一个tool_call_id的响应，少一个就直接400（insufficient tool messages following tool_calls message）
 * 所以只要还有任何一个调用停在待审批，就不能继续生成 —— 等用户处理完最后一个再续写
 */
private suspend fun ChatViewModel.continueIfAllToolsAnswered(cid: String, callId: String) {
    if (hasUnansweredToolCalls(cid, callId)) return
    // 续写回「发起这次工具调用的那条助手消息」，不要新开气泡（对齐rikkahub）
    val ownerId = store.snapshot().firstOrNull { it.id == cid }
        ?.messages
        ?.firstOrNull { msg -> msg.toolInvocations.any { it.callId == callId } }
        ?.id
    triggerGeneration(cid, reuseMsgId = ownerId)
}

private suspend fun ChatViewModel.hasUnansweredToolCalls(cid: String, callId: String): Boolean {
    val conv = store.snapshot().firstOrNull { it.id == cid } ?: return false
    val owner = conv.messages.firstOrNull { msg -> msg.toolInvocations.any { it.callId == callId } }
        ?: return false
    return owner.toolInvocations.any { it.state == ToolInvocationState.PENDING_APPROVAL }
}

private suspend fun ChatViewModel.updateToolInvocationState(
    cid: String,
    callId: String,
    state: String,
    resultOutput: String? = null
) {
    store.updateMessages(cid) { msgs ->
        msgs.map { msg ->
            if (msg.toolInvocations.none { it.callId == callId }) msg
            else msg.copy(
                toolInvocations = msg.toolInvocations.map { inv ->
                    if (inv.callId == callId) inv.copy(
                        state = state,
                        result = resultOutput ?: inv.result,
                        error = if (state == ToolInvocationState.ERROR) resultOutput else inv.error
                    ) else inv
                }
            )
        }
    }
}

private suspend fun ChatViewModel.executeTool(
    cid: String,
    toolName: String,
    argsJson: String
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        if (isWorkspaceTool(toolName)) {
            val workspaceId = workspaceIdFor(cid)
                ?: return@withContext false to "This assistant is not bound to a workspace"
            val res = executeWorkspaceTool(toolName, argsJson, workspaceId, workspaceRepository)
            return@withContext res.ok to res.content.ifBlank { res.error ?: "" }
        }

        if (BuiltInFileTools.isBuiltIn(toolName)) {
            val res = BuiltInFileTools.execute(toolName, argsJson)
            return@withContext res.ok to (if (res.ok) res.content else (res.error ?: "unknown error"))
        }

        val tool = mcpStore.toolsSnapshot().firstOrNull { it.name == toolName && it.serverId.isNotBlank() }
        val server = mcpStore.snapshot().firstOrNull { it.id == tool?.serverId }
            ?: return@withContext false to "No MCP server provides tool $toolName"
        val res = mcpClient.callTool(server, toolName, argsJson)
        res.ok to (if (res.ok) res.content else (res.error ?: "执行失败"))
    } catch (t: Throwable) {
        DebugLog.e("Tool", "execute $toolName failed", t)
        false to (t.message ?: t::class.simpleName ?: "error")
    }
}

/**
 * 自动放行本轮不需要人工审批的工具并串行执行，返回是否执行过（=需要继续生成）
 * 交互式澄清工具始终等待用户操作
 */
private suspend fun ChatViewModel.executeAutoApprovedTools(
    cid: String,
    invocations: List<ToolInvocation>
): Boolean {
    val workspace = workspaceFor(cid)
    val perms = mcpStore.permsSnapshot()
    // 本地跟踪每个调用的状态：invocations是生成时的快照，不会随落库更新
    val states = invocations.associate { it.callId to it.state }.toMutableMap()
    var executedAny = false

    for (inv in invocations) {
        if (states[inv.callId] != ToolInvocationState.PENDING_APPROVAL) continue
        if (inv.toolName == "interactive_clarification") continue

        val (enabled, requiresApproval) = if (isWorkspaceTool(inv.toolName)) {
            true to resolveWorkspaceToolApproval(inv.toolName, workspace?.toolApprovalOverrides().orEmpty())
        } else {
            val perm = perms.firstOrNull { it.toolName == inv.toolName }
            (perm?.enabled ?: true) to (perm?.requireApproval ?: false)
        }
        if (!enabled || requiresApproval) continue

        states[inv.callId] = ToolInvocationState.RUNNING
        updateToolInvocationState(cid, inv.callId, ToolInvocationState.RUNNING)
        val (ok, output) = executeTool(cid, inv.toolName, inv.arguments)
        states[inv.callId] = if (ok) ToolInvocationState.SUCCESS else ToolInvocationState.ERROR
        updateToolInvocationState(
            cid,
            inv.callId,
            if (ok) ToolInvocationState.SUCCESS else ToolInvocationState.ERROR,
            output
        )
        executedAny = true
    }

    // 只要还有调用停在待审批，就不能续写：缺任何一个tool响应，网关都会直接400
    val stillPending = states.values.any { it == ToolInvocationState.PENDING_APPROVAL }
    return executedAny && !stillPending
}

internal fun ChatViewModel.regenerate() {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val conv = store.snapshot().firstOrNull { it.id == cid } ?: return@launch
        val lastUserIdx = conv.messages.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return@launch
        store.upsert(
            conv.copy(messages = conv.messages.subList(0, lastUserIdx + 1), updatedAt = System.currentTimeMillis())
        )
        triggerGeneration(cid)
    }
}

/**
 * 重说：从[messageId]所在位置重新生成。
 * 目标为用户消息：保留该消息本身，重新生成它后面的助手回复
 * 目标为助手消息：连同该消息一起丢掉后重新生成
 */
internal fun ChatViewModel.regenerateFrom(messageId: String) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val conv = store.snapshot().firstOrNull { it.id == cid } ?: return@launch
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return@launch
        val target = conv.messages[idx]
        val keep = if (target.role == "user") idx + 1 else idx
        // 截断后必须仍有用户消息，否则没有可重说的内容
        if (keep <= 0 || conv.messages.take(keep).none { it.role == "user" }) return@launch
        store.upsert(
            conv.copy(messages = conv.messages.subList(0, keep), updatedAt = System.currentTimeMillis())
        )
        triggerGeneration(cid)
    }
}

internal fun ChatViewModel.continueGenerating() {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val conv = store.snapshot().firstOrNull { it.id == cid } ?: return@launch
        val last = conv.messages.lastOrNull() ?: return@launch
        if (last.role != "assistant" || last.content.isEmpty()) return@launch
        store.updateMessages(cid) { msgs ->
            msgs.dropLast(1) + Message(id = newId(), role = "user", content = "Continue exactly where you left off.")
        }
        triggerGeneration(cid)
    }
}

internal fun ChatViewModel.deleteMessage(messageId: String) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        store.updateMessages(cid) { it.filterNot { m -> m.id == messageId } }
    }
}

internal fun ChatViewModel.editMessage(messageId: String, newContent: String) {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        store.updateMessages(cid) { msgs ->
            msgs.map { if (it.id == messageId) it.copy(content = newContent) else it }
        }
    }
}


private fun ChatViewModel.assistantForConversation(conv: Conversation): Assistant? =
    assistants.value.firstOrNull { it.id == conv.assistantId }
        ?: assistants.value.firstOrNull { it.id == settings.value.activeAssistantId }

internal fun ChatViewModel.workspaceIdFor(cid: String): String? {
    val conv = conversations.value.firstOrNull { it.id == cid } ?: return null
    return effectiveWorkspaceId(conv, assistantForConversation(conv))?.takeIf { it.isNotBlank() }
}

internal fun ChatViewModel.workspaceFor(cid: String): WorkspaceEntity? {
    val id = workspaceIdFor(cid) ?: return null
    return workspaceEntity(id)
}

internal fun ChatViewModel.workspaceEntity(id: String): WorkspaceEntity? =
    workspaces.value.firstOrNull { it.id == id }

/** 本轮可用工具：内置文件工具+对话生效的工作区工具+对话生效的MCP服务器工具 */
internal suspend fun ChatViewModel.collectToolSpecs(
    conv: Conversation?,
    assistant: Assistant?,
): List<ToolSpec> {
    val specs = mutableListOf<ToolSpec>()
    BuiltInFileTools.schema.forEach { specs += ToolSpec(it.name, it.description, it.inputSchema) }

    val enabledServerIds = effectiveMcpServerIds(conv, assistant)
    val servers = mcpStore.snapshot().filter { server ->
        server.enabled && server.id in enabledServerIds
    }
    val perms = mcpStore.permsSnapshot()
    mcpStore.toolsSnapshot()
        .filter { tool -> servers.any { it.id == tool.serverId } }
        .filter { tool ->
            perms.firstOrNull { it.toolName == tool.name && it.serverId == tool.serverId }?.enabled ?: true
        }
        .forEach { specs += ToolSpec(it.name, it.description, it.inputSchema) }

    if (!effectiveWorkspaceId(conv, assistant).isNullOrBlank()) {
        workspaceToolSpecs().forEach { specs += ToolSpec(it.name, it.description, it.inputSchema) }
    }
    return specs
}

private suspend fun ChatViewModel.buildApiMessages(
    conv: Conversation,
    assistant: Assistant?,
    provider: ProviderConfig,
    model: String
): List<ChatMessage> {
    val app = getApplication<Application>()
    val resolver = app.contentResolver
    val messages = mutableListOf<ChatMessage>()

    val enabledSkills = activeEnabledSkills(assistant)
    val skillsBlock = if (enabledSkills.isEmpty()) "" else buildString {
        append("\n\n# Active Skills\n")
        enabledSkills.forEach { s ->
            append("## ").append(s.name).append('\n')
            if (s.description.isNotBlank()) append('_').append(s.description).append("_\n")
            append(s.body.trim()).append("\n\n")
        }
    }
    val baseSystem = assistant?.systemPrompt?.takeIf { it.isNotBlank() } ?: settings.value.systemPrompt
    val tools = collectToolSpecs(conv, assistant)
    val systemPrompt = PromptVars.render(
        template = baseSystem + skillsBlock + if (tools.isEmpty()) "" else agentToolHint(),
        model = model,
        provider = provider.name,
        assistant = assistant?.name ?: ""
    )
    if (systemPrompt.isNotBlank()) messages += ChatMessage(role = "system", content = systemPrompt)

    val truncatedNames = mutableListOf<String>()
    val notInlinedNames = mutableListOf<String>()

    for (msg in conv.messages) {
        when (msg.role) {
            "user" -> {
                val images = msg.attachments.filter { it.type == "image" }
                val others = msg.attachments.filter { it.type != "image" }
                if (images.isNotEmpty()) {
                    val parts = mutableListOf<com.miniichatNext.carter.api.ChatPart>()
                    if (msg.content.isNotBlank()) parts.add(com.miniichatNext.carter.api.ChatPart.Text(msg.content))
                    for (att in images) {
                        val loaded = runCatching {
                            AttachmentLoader.load(
                                resolver = resolver,
                                uri = android.net.Uri.parse(att.uri),
                                mimeFallback = att.mimeType.ifBlank { "image/jpeg" }
                            )
                        }.getOrNull() ?: continue
                        parts.add(
                            com.miniichatNext.carter.api.ChatPart.ImageUrl(
                                com.miniichatNext.carter.api.ChatPart.ImageUrl.ImageUrlData(
                                    url = "data:${loaded.mimeType};base64,${loaded.base64}"
                                )
                            )
                        )
                    }
                    if (others.isNotEmpty()) {
                        val blocks = others.map { buildAttachmentBlock(resolver, it) }
                        blocks.forEach {
                            if (it.truncated) truncatedNames += it.name
                            if (!it.inlined) notInlinedNames += it.name
                        }
                        val tail = blocks.joinToString("\n\n") { it.text }
                        val first = parts.firstOrNull()
                        val merged = if (first is com.miniichatNext.carter.api.ChatPart.Text) {
                            listOf(com.miniichatNext.carter.api.ChatPart.Text(first.text + "\n\n" + tail)) + parts.drop(1)
                        } else {
                            listOf(com.miniichatNext.carter.api.ChatPart.Text(tail)) + parts
                        }
                        messages += ChatMessage(role = msg.role, content = msg.content, parts = merged)
                    } else {
                        messages += ChatMessage(role = msg.role, content = msg.content, parts = parts)
                    }
                } else if (others.isNotEmpty()) {
                    val blocks = others.map { buildAttachmentBlock(resolver, it) }
                    blocks.forEach {
                        if (it.truncated) truncatedNames += it.name
                        if (!it.inlined) notInlinedNames += it.name
                    }
                    val blockText = blocks.joinToString("\n\n") { it.text }
                    val combined = if (msg.content.isBlank()) blockText else "${msg.content}\n\n$blockText"
                    messages += ChatMessage(role = msg.role, content = combined)
                } else if (msg.content.isNotBlank()) {
                    messages += ChatMessage(role = msg.role, content = msg.content)
                }
            }

            "assistant" -> {
                val toolCalls = msg.toolInvocations.map {
                    ToolCall(id = it.callId, name = it.toolName, arguments = it.arguments)
                }
                if (msg.content.isNotBlank() || toolCalls.isNotEmpty()) {
                    messages += ChatMessage(
                        role = "assistant",
                        content = msg.content,
                        toolCalls = toolCalls.takeIf { it.isNotEmpty() }
                    )
                }
                // 工具结果紧跟其后的assistant消息，作为独立tool轮次回放
                // 每个tool_call_id都必须有对应的tool消息，少一个网关就报400
                msg.toolInvocations.forEach { inv ->
                    val content = inv.result.ifBlank {
                        when (inv.state) {
                            ToolInvocationState.REJECTED -> "User rejected this tool call."
                            else -> "Tool call was not completed (state=${inv.state})."
                        }
                    }
                    messages += ChatMessage(role = "tool", content = content, toolCallId = inv.callId)
                }
            }

            else -> Unit
        }
    }

    // 末尾悬空的assistant（没有tool_calls、也没有后续tool结果）会让部分网关报错
    while (messages.isNotEmpty() && messages.last().role == "assistant" && messages.last().toolCalls.isNullOrEmpty()) {
        messages.removeAt(messages.lastIndex)
    }

    val notes = buildList {
        if (truncatedNames.isNotEmpty()) add("已截断：" + truncatedNames.joinToString("、"))
        if (notInlinedNames.isNotEmpty()) add("未内联：" + notInlinedNames.joinToString("、"))
    }
    if (notes.isNotEmpty()) {
        _toast.value = app.getString(
            com.miniichatNext.carter.R.string.attachment_inline_note,
            notes.joinToString("；")
        )
    }
    return messages
}

private fun agentToolHint(): String = buildString {
    append("\n\n# Agent Tools\n")
    append("你可以在合适的时候调用工具。规则：\n")
    append("- 只有用户明确需要时才调用工具，不要为了显得有用而乱调\n")
    append("- 用户指向不明确时先问清楚，不要盲目列举目录来猜\n")
    append("- 同一次请求里不要重复调用同一个工具\n")
    append("- 工具参数必须传具体值\n")
}

/** 附件内联上限（与AttachmentLoader.MAX_INLINE_TEXT_BYTES对齐） */
private const val ATTACHMENT_INLINE_LIMIT = 512L * 1024

private data class AttachmentBlock(
    val name: String,
    val text: String,
    val inlined: Boolean,
    val truncated: Boolean = false
)

/**
 * 把附件拼成可发给模型的文本块：文本文件直接内联（超限截断并标注）
 * 二进制/读取失败只给文件信息与原因
 */
private fun buildAttachmentBlock(
    resolver: android.content.ContentResolver,
    att: Attachment
): AttachmentBlock {
    val head = "===== 附件：${att.name} (${att.mimeType}) ====="
    val limitKb = ATTACHMENT_INLINE_LIMIT / 1024
    return when (
        val r = AttachmentLoader.readAsText(
            resolver, android.net.Uri.parse(att.uri), ATTACHMENT_INLINE_LIMIT
        )
    ) {
        is InlineAttachment.Text -> {
            val total = maxOf(att.sizeBytes, r.sizeBytes)
            val body = buildString {
                append(head).append(' ').append(AttachmentLoader.formatBytes(total)).append('\n')
                append(r.text)
                if (r.truncated) {
                    append("\n…（已截断：文件共 ")
                    append(AttachmentLoader.formatBytes(total))
                    append("，仅内联前 $limitKb KB）")
                }
                append("\n附件结束：${att.name}")
            }
            AttachmentBlock(att.name, body, inlined = true, truncated = r.truncated)
        }
        is InlineAttachment.Skipped -> AttachmentBlock(
            att.name,
            "$head ${AttachmentLoader.formatBytes(r.sizeBytes)}\n（${r.reason}，内容未内联）",
            inlined = false
        )
        InlineAttachment.Failed -> AttachmentBlock(
            att.name,
            "$head\n（读取失败，仅提供文件信息）",
            inlined = false
        )
    }
}

private val TOOL_JSON = Json { ignoreUnknownKeys = true }

internal fun parseJsonSafely(json: String): JsonObject =
    runCatching { TOOL_JSON.parseToJsonElement(json).jsonObject }
        .getOrElse { buildJsonObject { } }

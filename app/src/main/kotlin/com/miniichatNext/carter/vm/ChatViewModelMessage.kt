package com.miniichatNext.carter.vm

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.R
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.ToolCall
import com.miniichatNext.carter.api.ToolSpec
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.mcp.EffectiveToolPermission
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.exposedMcpToolName
import com.miniichatNext.carter.data.mcp.resolveToolPermission
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
import com.miniichatNext.carter.data.workspace.resolveWorkspaceToolApprovalByCategory
import com.miniichatNext.carter.data.workspace.WORKSPACE_SERVER_ID
import com.miniichatNext.carter.data.workspace.workspaceToolSpecs
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.data.util.AttachmentLoader
import com.miniichatNext.carter.data.util.InlineAttachment
import com.miniichatNext.carter.data.util.PromptVars
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    // store.snapshot()是suspend，sendMessage本身不是suspend——先取内存缓存的conversations StateFlow
    val conv = conversations.value.firstOrNull { it.id == _activeId.value }

    val (provider, model) = effectiveConversationModel(conv, assistant, current, providers.value)
        ?: run {
            _error.value = app.getString(com.miniichatNext.carter.R.string.error_no_provider)
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

    val activeId = ensureActiveConversationId()
    val userMsg = Message(id = newId(), role = "user", content = trimmed, attachments = attachments)
    stopTaskSession()
    val session = ChatTaskSession(activeId, viewModelScope)
    taskSession = session
    launchGeneration(session, null, userMsg, current.activeAssistantId)

}

/** 从持久化消息重建请求历史并发起流式生成；自动放行的工具会串行执行后继续生成 */
/**
 * 跑完一整轮生成→工具→生成…
 *
 * [reuseMsgId]不为空时会续写那一条助手消息（工具调用后继续生成属于同一轮回复），而不是每条消息开一个新气泡
 */
internal fun ChatViewModel.triggerGeneration(cid: String, reuseMsgId: String? = null) {
    stopTaskSession()
    val session = ChatTaskSession(cid, viewModelScope)
    taskSession = session
    launchGeneration(session, reuseMsgId)
}

private fun ChatViewModel.ownsSession(session: ChatTaskSession): Boolean =
    taskSession === session && session.isActive

private fun ChatViewModel.launchGeneration(
    session: ChatTaskSession,
    reuseMsgId: String?,
    initialMessage: Message? = null,
    assistantId: String = settings.value.activeAssistantId,
) {
    if (!ownsSession(session)) return
    val cid = session.conversationId
    _isStreaming.value = true
    _streamingConvId.value = cid
    val job = session.launchGeneration { version ->
        _isStreaming.value = true
        _streamingConvId.value = cid
        _hasPendingTools.value = false
        _pendingConvId.value = null
        _streamingOverlay.value = null
        var waitingApproval = false
        try {
            if (initialMessage != null) {
                val title = initialMessage.content.take(30).replace("\n", " ")
                store.updateConversation(cid,
                    create = { Conversation(id = cid, title = title, assistantId = assistantId) },
                ) { current ->
                    current.copy(title = if (current.messages.isEmpty()) title else current.title,
                        messages = current.messages + initialMessage)
                }
            }
            var round = 0
            var reuse = reuseMsgId
            while (round < MAX_TOOL_ROUNDS) {
                currentCoroutineContext().ensureActive()
                round++
                val res = runGenerationRound(cid, reuse) ?: break
                reuse = res.msgId
                if (res.pending.isEmpty()) break
                if (!executeAutoApprovedTools(cid, res.pending)) {
                    val latest = store.snapshot().firstOrNull { it.id == cid }
                        ?.messages?.firstOrNull { it.id == res.msgId }
                    waitingApproval = latest?.toolInvocations?.any { it.isUnanswered() } == true
                    _hasPendingTools.value = waitingApproval
                    _pendingConvId.value = cid.takeIf { waitingApproval }
                    break
                }
                if (round == MAX_TOOL_ROUNDS) {
                    _error.value = getApplication<Application>().getString(R.string.tool_round_limit)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ownsSession(session)) _error.value = e.message ?: "Request failed"
            DebugLog.e("ChatVM", "generation failed", e)
        } finally {
            if (ownsSession(session) && session.generationVersion == version) {
                _streamingOverlay.value = null
                _isStreaming.value = false
                _streamingConvId.value = null
                streamingJob = null
                _hasPendingTools.value = waitingApproval
                _pendingConvId.value = cid.takeIf { waitingApproval }
            }
        }
    }
    streamingJob = job.takeIf { ownsSession(session) && it.isActive }
}

private fun ToolInvocation.isUnanswered(): Boolean =
    state == ToolInvocationState.PENDING_APPROVAL || state == ToolInvocationState.EXECUTING

private const val MAX_TOOL_ROUNDS = 8
/** 流式期间DataStore节流落库间隔（ms）；toolCalls delta在流末尾统一到达，流结束后的强制落库兜底 */
private const val PERSIST_INTERVAL_MS = 800L
private const val STREAM_OVERLAY_INTERVAL_MS = 33L

/** 跑一轮流式生成并落库，返回本轮待处理的工具调用；返回null表示无法继续 */
private data class RoundResult(val msgId: String, val pending: List<ToolInvocation>)

private suspend fun ChatViewModel.runGenerationRound(cid: String, reuseMsgId: String?): RoundResult? {
    val conv = store.snapshot().firstOrNull { it.id == cid } ?: return null
    currentCoroutineContext().ensureActive()
    val assistant = assistantForConversation(conv)
    val (provider, model) = effectiveConversationModel(conv, assistant, settings.value, providers.value) ?: run {
        _error.value = getApplication<Application>()
            .getString(com.miniichatNext.carter.R.string.error_no_provider)
        return null
    }

    // 读附件/base64编码开销大：viewModelScope默认Main，必须挪到IO，否则附件一多就卡
    val apiMessages = withContext(Dispatchers.IO) { buildApiMessages(conv, assistant, provider, model) }
    if (apiMessages.none { it.role == "user" || it.role == "tool" }) return null

    // 模型不支持工具调用（provider/model级能力标记）→不提供任何工具
    val tools = withContext(Dispatchers.IO) {
        if (provider.supportsToolCall(model)) collectToolSpecs(conv, assistant) else emptyList()
    }
    DebugLog.i("Tools", "collectToolSpecs: total=" + tools.size + " workspaceId=" + (conv.toolWorkspaceId ?: "unbound"))
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
    // 节流落库：流式delta高频（DeepSeek思考每秒几十个），每delta全量写DataStore会打爆IO队列
    var lastPersistAt = 0L
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

    // orderedParts（节点化）：LLM真实输出顺序的思考/工具/正文
    // 续写场景：旧消息没有orderedParts→按content+reasoning+tools拼一个保底版
    val parts: MutableList<com.miniichatNext.carter.data.model.MessageSegment> =
        (existing?.orderedParts?.takeIf { it.isNotEmpty() }?.toMutableList()
            ?: buildList {
                existing?.reasoningContent?.takeIf { it.isNotBlank() }
                    ?.let { add(com.miniichatNext.carter.data.model.MessageSegment.Reasoning(it)) }
                existing?.toolInvocations
                    ?.forEach { add(com.miniichatNext.carter.data.model.MessageSegment.Tool(it.callId)) }
                existing?.content?.takeIf { it.isNotBlank() }
                    ?.let { add(com.miniichatNext.carter.data.model.MessageSegment.Text(it)) }
            }.toMutableList())
    // 先在StringBuilder里聚合delta，避免每个token都复制整个末尾段字符串。
    val pendingText = StringBuilder()
    val pendingReasoning = StringBuilder()

    fun flushPendingParts() {
        if (pendingText.isNotEmpty()) {
            val last = parts.lastOrNull()
            if (last is com.miniichatNext.carter.data.model.MessageSegment.Text) {
                parts[parts.lastIndex] = last.copy(text = last.text + pendingText.toString())
            } else {
                parts.add(com.miniichatNext.carter.data.model.MessageSegment.Text(pendingText.toString()))
            }
            pendingText.setLength(0)
        }
        if (pendingReasoning.isNotEmpty()) {
            val last = parts.lastOrNull()
            if (last is com.miniichatNext.carter.data.model.MessageSegment.Reasoning) {
                parts[parts.lastIndex] = last.copy(text = last.text + pendingReasoning.toString())
            } else {
                parts.add(com.miniichatNext.carter.data.model.MessageSegment.Reasoning(pendingReasoning.toString()))
            }
            pendingReasoning.setLength(0)
        }
    }

    fun appendText(chunk: String) {
        if (pendingReasoning.isNotEmpty()) flushPendingParts()
        pendingText.append(chunk)
    }

    fun appendReasoning(chunk: String) {
        if (pendingText.isNotEmpty()) flushPendingParts()
        pendingReasoning.append(chunk)
    }

    fun mergedParts(): List<com.miniichatNext.carter.data.model.MessageSegment> {
        flushPendingParts()
        return parts.toList()
    }

    var lastOverlayAt = 0L
    fun publishOverlay(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastOverlayAt < STREAM_OVERLAY_INTERVAL_MS) return
        lastOverlayAt = now
        _streamingOverlay.value = StreamOverlay(
            assistantMsg.id,
            mergedContent(),
            mergedReasoning(),
            mergedParts(),
        )
    }

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
                appendText(delta.text)
                publishOverlay()
            }
            if (delta.reasoning.isNotEmpty()) {
                reasoning.append(delta.reasoning)
                appendReasoning(delta.reasoning)
                publishOverlay()
            }
            delta.toolCalls.forEach { tc ->
                taskSession?.takeIf { it.conversationId == cid }?.rememberTool(tc.id)
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
                    // 工具节点：按出现顺序追加；后续同id的delta只更新参数，不再重复append
                    flushPendingParts()
                    parts.add(com.miniichatNext.carter.data.model.MessageSegment.Tool(tc.id))
                }
            }
            if (delta.toolCalls.isNotEmpty()) publishOverlay()

            val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)
            if (delta.usage != null) usage = delta.usage
            val now = SystemClock.elapsedRealtime()
            if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                lastPersistAt = now
                persistAssistantStep(cid, assistantMsg.id, mergedContent(), mergedReasoning(), invocations, usage, orderedParts = mergedParts())
            }
        }

    // usage为空时按字符数估算（原逻辑在每delta里算，节流后移到流末补一次）
    if (usage == null) {
        val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)
        val est = (text.length / 2).coerceAtLeast(1)
        usage = TokenUsage(
            completionTokens = est,
            totalTokens = est,
            durationMs = elapsed,
            tokensPerSecond = (est * 1000f) / elapsed
        )
    }
    if (text.isBlank() && invocations.isEmpty()) {
        // 只在首轮且完全没产出时才写占位符，续写时不要覆盖掉之前的内容
        if (priorContent.isBlank()) {
            val fallback = if (reasoning.isNotBlank())
                getApplication<Application>().getString(R.string.chat_reasoning_only)
            else getApplication<Application>().getString(R.string.chat_no_content)
            persistAssistantStep(cid, assistantMsg.id, fallback, mergedReasoning(), invocations, usage, orderedParts = mergedParts())
        }
    } else {
        persistAssistantStep(cid, assistantMsg.id, mergedContent(), mergedReasoning(), invocations, usage, orderedParts = mergedParts())
    }

    settingsRepo.addTokenUsage(usage)
    generateTitle(cid)
    // 候选词只在用户打开候选面板时才生成（SuggestionPanel的LaunchedEffect触发）
    // 只返回仍待用户审批的调用：已执行完的（含上一轮续写携带）不能再算pending，
    // 否则会误判为"等待用户操作"，busy状态永远不清（表现为"已完成仍显示回复中"）
    return RoundResult(
        assistantMsg.id,
        invocations.filter { it.state == ToolInvocationState.PENDING_APPROVAL }
    )
}

private suspend fun ChatViewModel.persistAssistantStep(
    cid: String,
    msgId: String,
    content: String,
    reasoning: String,
    invocations: List<ToolInvocation>,
    usage: TokenUsage?,
    orderedParts: List<com.miniichatNext.carter.data.model.MessageSegment> = emptyList(),
) {
    store.updateMessages(cid) { msgs ->
        msgs.map { m ->
            if (m.id == msgId) m.copy(
                content = content,
                reasoningContent = reasoning.ifBlank { null },
                toolInvocations = invocations.toList(),
                orderedParts = orderedParts,
                usage = usage ?: m.usage
            ) else m
        }
    }
    _streamingOverlay.value = StreamOverlay(msgId, content, reasoning, orderedParts)
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
    val session = sessionForApproval(cid, inv.callId) ?: return
    session.launchTool(inv.callId) {
        if (!ownsSession(session)) return@launchTool
        try {
            val claimed = claimPendingTool(cid, inv.callId, ToolInvocationState.RUNNING) ?: return@launchTool
            val available = resolveAvailableTool(cid, claimed.toolName)
            val result = if (!available.permission.enabled) {
                false to "Tool is disabled or not available in this conversation"
            } else if (!customResult.isNullOrBlank() && claimed.toolName == "interactive_clarification") {
                true to customResult
            } else {
                executeTool(cid, claimed.toolName, claimed.arguments)
            }
            currentCoroutineContext().ensureActive()
            if (!ownsSession(session)) return@launchTool
            updateToolInvocationState(cid, inv.callId,
                if (result.first) ToolInvocationState.SUCCESS else ToolInvocationState.ERROR, result.second)
            continueIfAllToolsAnswered(session, inv.callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ownsSession(session)) {
                updateToolInvocationState(cid, inv.callId, ToolInvocationState.ERROR, e.message ?: "Tool failed")
                continueIfAllToolsAnswered(session, inv.callId)
            }
        }
    }
}

internal fun ChatViewModel.rejectToolCall(cid: String, inv: ToolInvocation) {
    val session = sessionForApproval(cid, inv.callId) ?: return
    session.launchTool(inv.callId) {
        if (!ownsSession(session)) return@launchTool
        try {
            claimPendingTool(cid, inv.callId, ToolInvocationState.REJECTED) ?: return@launchTool
            updateToolInvocationState(cid, inv.callId, ToolInvocationState.REJECTED, "User rejected this tool call.")
            continueIfAllToolsAnswered(session, inv.callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ownsSession(session)) _error.value = e.message ?: "Tool failed"
            DebugLog.e("Tool", "reject failed", e)
        }
    }
}

private fun ChatViewModel.sessionForApproval(cid: String, callId: String): ChatTaskSession? {
    if (callId in stoppedToolCalls) return null
    val current = taskSession
    if (current != null && current.isActive) {
        if (current.conversationId == cid) {
            current.rememberTool(callId)
            return current
        }
        if (_isStreaming.value || _hasPendingTools.value) return null
        stopTaskSession()
    }
    val pending = conversations.value.firstOrNull { it.id == cid }?.messages
        ?.flatMap { it.toolInvocations }
        ?.any { it.callId == callId && it.state == ToolInvocationState.PENDING_APPROVAL } == true
    if (!pending) return null
    return ChatTaskSession(cid, viewModelScope).also {
        taskSession = it
        it.rememberTool(callId)
        _hasPendingTools.value = true
        _pendingConvId.value = cid
    }
}

private suspend fun ChatViewModel.claimPendingTool(
    cid: String,
    callId: String,
    state: String,
): ToolInvocation? {
    var claimed: ToolInvocation? = null
    store.updateMessages(cid) { msgs ->
        msgs.map { msg ->
            msg.copy(toolInvocations = msg.toolInvocations.map { inv ->
                if (inv.callId == callId && callId !in stoppedToolCalls &&
                    inv.state == ToolInvocationState.PENDING_APPROVAL && claimed == null) {
                    claimed = inv
                    inv.copy(state = state)
                } else inv
            })
        }
    }
    return claimed
}

private suspend fun ChatViewModel.continueIfAllToolsAnswered(session: ChatTaskSession, callId: String) {
    val cid = session.conversationId
    if (!ownsSession(session)) return
    val owner = store.snapshot().firstOrNull { it.id == cid }?.messages
        ?.firstOrNull { msg -> msg.toolInvocations.any { it.callId == callId } } ?: return
    if (owner.toolInvocations.any { it.isUnanswered() || it.state == ToolInvocationState.CANCELLED }) return
    val batchKey = owner.id + ":" + owner.toolInvocations.joinToString(",") { it.callId }
    if (session.claimContinuation(batchKey)) launchGeneration(session, owner.id)
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
                    if (inv.callId == callId && callId !in stoppedToolCalls &&
                        inv.state != ToolInvocationState.CANCELLED) inv.copy(
                        state = state,
                        result = resultOutput ?: inv.result,
                        error = if (state == ToolInvocationState.ERROR) resultOutput else inv.error
                    ) else inv
                }
            )
        }
    }
}

private fun toolTargetsPublicStorage(toolName: String, argsJson: String): Boolean {
    if (toolName == "execute_shell") return true
    if (!BuiltInFileTools.isBuiltIn(toolName)) return false
    val args = runCatching { AppJson.parseObject(argsJson) }.getOrDefault(JsonObject(emptyMap()))
    val paths = listOf("path", "from", "to").mapNotNull { key ->
        args[key]?.jsonPrimitive?.contentOrNull
    }
    if (paths.isEmpty()) return true
    return paths.any { raw ->
        val path = raw.trim()
        !path.startsWith("/data/") && !path.startsWith("/proc/") &&
            !path.startsWith("/system/") && !path.startsWith("/storage/emulated/0/Android/data/")
    }
}

private fun hasAllFilesAccessPermission(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.os.Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private data class AvailableTool(
    val permission: EffectiveToolPermission,
    val server: McpServerConfig? = null,
    val workspaceId: String? = null,
)

private suspend fun ChatViewModel.resolveAvailableTool(cid: String, name: String): AvailableTool {
    val disabled = AvailableTool(EffectiveToolPermission(false, true))
    val conv = store.snapshot().firstOrNull { it.id == cid } ?: return disabled
    val assistant = assistantForConversation(conv)
    val perms = mcpStore.permsSnapshot()
    if (isWorkspaceTool(name)) {
        val id = effectiveWorkspaceId(conv, assistant) ?: return disabled
        val workspace = workspaceStore.snapshot().firstOrNull { it.id == id } ?: return disabled
        val overrides = workspace.toolApprovalOverrides()
        return AvailableTool(resolveToolPermission(name, WORKSPACE_SERVER_ID, perms, true,
            categoryApproval = resolveWorkspaceToolApprovalByCategory(name, overrides, overrides)), workspaceId = id)
    }
    if (BuiltInFileTools.isBuiltIn(name)) {
        return AvailableTool(resolveToolPermission(name, "", perms, BuiltInFileTools.defaultRequireApproval(name)))
    }
    val ids = effectiveMcpServerIds(conv, assistant)
    val servers = mcpStore.snapshot().filter { it.enabled && it.id in ids }
    val allTools = mcpStore.toolsSnapshot().filter { tool -> servers.any { it.id == tool.serverId } }
    val namespaced = allTools.firstOrNull { exposedMcpToolName(it.serverId, it.name) == name }
    val rawMatches = allTools.filter { it.name == name }
    val tool = namespaced ?: rawMatches.singleOrNull() ?: return disabled
    val server = servers.first { it.id == tool.serverId }
    return AvailableTool(resolveToolPermission(tool.name, server.id, perms, server.requireApproval), server)
}

private suspend fun ChatViewModel.executeTool(
    cid: String,
    toolName: String,
    argsJson: String
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val resolved = resolveAvailableTool(cid, toolName)
    if (!resolved.permission.enabled) return@withContext false to "Tool is disabled or not available in this conversation"
    if (toolTargetsPublicStorage(toolName, argsJson) && !hasAllFilesAccessPermission(getApplication())) {
        requestPermission(toolName)
        return@withContext false to getApplication<Application>().getString(R.string.permission_manage_storage_required)
    }
    try {
        if (isWorkspaceTool(toolName)) {
            val workspaceId = resolved.workspaceId
                ?: return@withContext false to "This assistant is not bound to a workspace"
            val res = executeWorkspaceTool(toolName, argsJson, workspaceId, workspaceRepository)
            return@withContext res.ok to res.content.ifBlank { res.error ?: "" }
        }

        if (BuiltInFileTools.isBuiltIn(toolName)) {
            val res = runInterruptible(Dispatchers.IO) { BuiltInFileTools.execute(getApplication(), toolName, argsJson) }
            return@withContext res.ok to (if (res.ok) res.content else (res.error ?: "unknown error"))
        }

        val server = resolved.server
            ?: return@withContext false to "No MCP server provides tool $toolName"
        val rawName = mcpStore.toolsSnapshot().firstOrNull { exposedMcpToolName(it.serverId, it.name) == toolName }?.name ?: toolName
        val res = mcpClient.callTool(server, rawName, argsJson)
        res.ok to (if (res.ok) res.content
            else (res.error ?: getApplication<Application>().getString(R.string.common_execution_failed)))
    } catch (e: CancellationException) {
        throw e
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
    var answeredAny = false
    for (inv in invocations) {
        currentCoroutineContext().ensureActive()
        val resolved = resolveAvailableTool(cid, inv.toolName)
        if (!resolved.permission.enabled) {
            if (claimPendingTool(cid, inv.callId, ToolInvocationState.REJECTED) != null) {
                updateToolInvocationState(cid, inv.callId, ToolInvocationState.REJECTED,
                    "Tool is disabled or not available in this conversation")
                answeredAny = true
            }
            continue
        }
        if (inv.toolName == "interactive_clarification" || resolved.permission.requireApproval) continue
        val claimed = claimPendingTool(cid, inv.callId, ToolInvocationState.RUNNING) ?: continue
        val (ok, output) = executeTool(cid, claimed.toolName, claimed.arguments)
        currentCoroutineContext().ensureActive()
        updateToolInvocationState(cid, inv.callId,
            if (ok) ToolInvocationState.SUCCESS else ToolInvocationState.ERROR, output)
        answeredAny = true
    }
    val ids = invocations.map { it.callId }.toSet()
    val current = store.snapshot().firstOrNull { it.id == cid }?.messages
        ?.flatMap { it.toolInvocations }?.filter { it.callId in ids }.orEmpty()
    return answeredAny && current.none { it.isUnanswered() }
}

internal fun ChatViewModel.regenerate() {
    val cid = _activeId.value ?: return
    stopTaskSession()
    viewModelScope.launch {
        val conv = store.snapshot().firstOrNull { it.id == cid } ?: return@launch
        val lastUserIdx = conv.messages.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return@launch
        store.updateMessages(cid) { current ->
            val index = current.indexOfLast { it.role == "user" }
            if (index < 0) current else current.take(index + 1)
        }
        triggerGeneration(cid)
    }
}

/**
 * 重说：从[messageId]所在位置重新生成。
 * 目标为用户消息：保留该消息本身，重新生成它后面的助手回复
 * 目标为助手消息：连同该消息一起丢掉后重新生成
 */
internal fun ChatViewModel.regenerateFrom(messageId: String) {
    stopTaskSession()
    viewModelScope.launch {
        val cid = findConvIdForMessage(messageId) ?: return@launch
        val conv = store.snapshot().firstOrNull { it.id == cid } ?: return@launch
        val idx = conv.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return@launch
        val target = conv.messages[idx]
        val keep = if (target.role == "user") idx + 1 else idx
        // 截断后必须仍有用户消息，否则没有可重说的内容
        if (keep <= 0 || conv.messages.take(keep).none { it.role == "user" }) return@launch
        store.updateMessages(cid) { current ->
            val index = current.indexOfFirst { it.id == messageId }
            if (index < 0) current else current.take(if (target.role == "user") index + 1 else index)
        }
        triggerGeneration(cid)
    }
}

internal fun ChatViewModel.continueGenerating() {
    val cid = _activeId.value ?: return
    viewModelScope.launch {
        val conv = store.snapshot().firstOrNull { it.id == cid } ?: return@launch
        val last = conv.messages.lastOrNull() ?: return@launch
        if (last.role != "assistant" || last.content.isEmpty()) return@launch
        // 追加一条隐形续写指令（UI不渲染）；新开一个AI回复气泡继续
        store.updateMessages(cid) { msgs ->
            msgs + Message(
                id = newId(),
                role = "user",
                content = "Continue exactly where you left off.",
                hidden = true,
            )
        }
        triggerGeneration(cid)
    }
}

internal fun ChatViewModel.deleteMessage(messageId: String) {
    viewModelScope.launch {
        val cid = findConvIdForMessage(messageId) ?: return@launch
        store.updateMessages(cid) { it.filterNot { m -> m.id == messageId } }
    }
}

/**
 * 按消息id反查所在会话id（不依赖_activeId，避免编辑非当前会话的消息时改错）。
 */
private suspend fun ChatViewModel.findConvIdForMessage(messageId: String): String? =
    store.snapshot().firstOrNull { conv -> conv.messages.any { it.id == messageId } }?.id

internal fun ChatViewModel.editMessage(messageId: String, newContent: String) {
    viewModelScope.launch {
        val cid = findConvIdForMessage(messageId) ?: return@launch
        store.updateMessages(cid) { msgs ->
            msgs.map { m ->
                if (m.id != messageId) m
                else m.copy(
                    content = newContent,
                    // 渲染走orderedParts；同时重写文本段，Reasoning/Tool段原位保留
                    orderedParts = rewriteTextSegments(m.orderedParts, newContent),
                )
            }
        }
    }
}

/** 编辑后重建orderedParts：Text段合并为新内容，Reasoning/Tool段原位保留 */
private fun rewriteTextSegments(
    parts: List<com.miniichatNext.carter.data.model.MessageSegment>,
    newText: String,
): List<com.miniichatNext.carter.data.model.MessageSegment> {
    if (parts.isEmpty()) return parts
    // 标准化语义：思考/工具段保持在前的相对顺序，编辑后的正文合并为最后一段。
    // 此前"合并文本放第一个Text位"会把夹在文本中间的工具段挤到正文之后（用户报：工具跑到消息最底部）。
    // 标准化后所有AI消息统一为"过程区在上、正文在下"，与工具后续写的原生结构一致。
    val result = buildList {
        parts.forEach { seg ->
            if (seg !is com.miniichatNext.carter.data.model.MessageSegment.Text) add(seg)
        }
        if (newText.isNotBlank()) add(com.miniichatNext.carter.data.model.MessageSegment.Text(newText))
    }
    return if (result.isEmpty()) parts else result
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
    val perms = mcpStore.permsSnapshot()
    BuiltInFileTools.schema(getApplication())
        .filter { resolveToolPermission(it.name, "", perms, BuiltInFileTools.defaultRequireApproval(it.name)).enabled }
        .forEach { specs += ToolSpec(it.name, it.description, it.inputSchema) }

    val enabledServerIds = effectiveMcpServerIds(conv, assistant)
    val servers = mcpStore.snapshot().filter { server ->
        server.enabled && server.id in enabledServerIds
    }
    mcpStore.toolsSnapshot()
        .filter { tool -> servers.any { it.id == tool.serverId } }
        .filter { tool ->
            perms.firstOrNull { it.toolName == tool.name && it.serverId == tool.serverId }?.enabled ?: true
        }
        .forEach { specs += ToolSpec(exposedMcpToolName(it.serverId, it.name), it.description, it.inputSchema) }

    val wsId = effectiveWorkspaceId(conv, assistant)
    if (!wsId.isNullOrBlank()) {
        workspaceToolSpecs()
            .filter { resolveToolPermission(it.name, WORKSPACE_SERVER_ID, perms, false).enabled }
            .forEach { specs += ToolSpec(it.name, it.description, it.inputSchema) }
    }
    val duplicates = specs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) DebugLog.w("Tools", "ambiguous tool names omitted: $duplicates")
    return specs.filterNot { it.name in duplicates }
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

    val enabledSkills = activeEnabledSkills(assistant, conv.id)
    val skillsBlock = if (enabledSkills.isEmpty()) "" else buildString {
        append("\n\n# Active Skills\n")
        enabledSkills.forEach { s ->
            append("## ").append(s.name).append('\n')
            if (s.description.isNotBlank()) append('_').append(s.description).append("_\n")
            append(s.body.trim()).append("\n\n")
        }
    }
    val baseSystem = assistant?.systemPrompt?.takeIf { it.isNotBlank() } ?: settings.value.systemPrompt
    val tools = if (provider.supportsToolCall(model)) collectToolSpecs(conv, assistant) else emptyList()
    val systemPrompt = PromptVars.render(
        template = baseSystem + skillsBlock + if (tools.isEmpty()) "" else agentToolHint(app),
        model = model,
        provider = provider.name,
        assistant = assistant?.name ?: ""
    )
    if (systemPrompt.isNotBlank()) messages += ChatMessage(role = "system", content = systemPrompt)

    val truncatedNames = mutableListOf<String>()
    val notInlinedNames = mutableListOf<String>()
    val readFailedNames = mutableListOf<String>()

    for (msg in conv.messages) {
        when (msg.role) {
            "user" -> {
                // 模型能力分派：支持vision直接发图；不支持时用OCR辅助模型转文字描述
                val supportsVision = provider.supportsImageInput(model)
                val images = if (supportsVision) {
                    msg.attachments.filter { it.type == "image" }
                } else {
                    emptyList()
                }
                // OCR模式分派：off=禁用/auto=仅vision-less主模型/always=所有带图消息
                // （always下视觉模型原图照发+附加文字块——图表/文字密集图把握更准）
                val ocrMode = settings.value.ocrMode
                val msgImages = msg.attachments.filter { it.type == "image" }
                val ocrDescriptions = when {
                    ocrMode == "off" -> emptyMap()
                    ocrMode == "always" && msgImages.isNotEmpty() ->
                        describeImagesWithOcr(msgImages, resolver)
                    !supportsVision && msgImages.isNotEmpty() ->
                        describeImagesWithOcr(msgImages, resolver)
                    else -> emptyMap()
                }
                val ocrBlock = ocrDescriptions.values.joinToString("\n\n") { it }
                val others = msg.attachments.filter { it.type != "image" }
                if (images.isNotEmpty()) {
                    val parts = mutableListOf<com.miniichatNext.carter.api.ChatPart>()
                    // always模式：视觉模型也收到OCR文字块（先拼进首个Text part）
                    val headText = buildString {
                        if (msg.content.isNotBlank()) append(msg.content)
                        if (ocrBlock.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(app.getString(R.string.ocr_describe_block_head)).append('\n')
                            append(ocrBlock)
                        }
                    }
                    if (headText.isNotBlank()) parts.add(com.miniichatNext.carter.api.ChatPart.Text(headText))
                    for (att in images) {
                        val loaded = runCatching {
                            AttachmentLoader.load(
                                resolver = resolver,
                                uri = android.net.Uri.parse(att.uri),
                                mimeFallback = att.mimeType.ifBlank { "image/jpeg" }
                            )
                        }.getOrNull()
                        if (loaded == null) {
                            readFailedNames += att.name
                            continue
                        }
                        parts.add(
                            com.miniichatNext.carter.api.ChatPart.ImageUrl(
                                com.miniichatNext.carter.api.ChatPart.ImageUrl.ImageUrlData(
                                    url = "data:${loaded.mimeType};base64,${loaded.base64}"
                                )
                            )
                        )
                    }
                    if (others.isNotEmpty()) {
                        val blocks = others.map { buildAttachmentBlock(app, resolver, it) }
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
                } else if (others.isNotEmpty() || ocrBlock.isNotBlank()) {
                    val blocks = others.map { buildAttachmentBlock(app, resolver, it) }
                    blocks.forEach {
                        if (it.truncated) truncatedNames += it.name
                        if (!it.inlined) notInlinedNames += it.name
                    }
                    val blockText = blocks.joinToString("\n\n") { it.text }
                    val combined = buildString {
                        if (msg.content.isNotBlank()) append(msg.content)
                        if (blockText.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(blockText)
                        }
                        if (ocrBlock.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(app.getString(R.string.ocr_describe_block_head)).append('\n')
                            append(ocrBlock)
                        }
                    }
                    messages += ChatMessage(role = msg.role, content = combined)
                } else if (msg.content.isNotBlank() || ocrBlock.isNotBlank()) {
                    val combined = if (ocrBlock.isBlank()) msg.content
                        else msg.content + "\n\n" + app.getString(R.string.ocr_describe_block_head) + "\n" + ocrBlock
                    messages += ChatMessage(role = msg.role, content = combined)
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
                        toolCalls = toolCalls.takeIf { it.isNotEmpty() },
                        reasoningContent = msg.reasoningContent?.takeIf { it.isNotBlank() },
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

    val listSeparator = app.getString(R.string.common_enum_separator)
    val notes = buildList {
        if (truncatedNames.isNotEmpty())
            add(app.getString(R.string.attachment_list_truncated, truncatedNames.joinToString(listSeparator)))
        if (notInlinedNames.isNotEmpty())
            add(app.getString(R.string.attachment_list_not_inlined, notInlinedNames.joinToString(listSeparator)))
        if (readFailedNames.isNotEmpty())
            add(app.getString(R.string.attachment_read_failed, readFailedNames.joinToString(listSeparator)))
    }
    if (notes.isNotEmpty()) {
        _toast.trySend(app.getString(
            R.string.attachment_inline_note,
            notes.joinToString(app.getString(R.string.common_note_separator)))
        )
    }
    return messages
}

private fun agentToolHint(context: android.content.Context): String =
    context.getString(R.string.agent_tools_hint)

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
    context: android.content.Context,
    resolver: android.content.ContentResolver,
    att: Attachment
): AttachmentBlock {
    val head = context.getString(R.string.attachment_block_head, att.name, att.mimeType)
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
                    append(context.getString(
                        R.string.attachment_truncated_note,
                        AttachmentLoader.formatBytes(total),
                        limitKb
                    ))
                }
                append(context.getString(R.string.attachment_block_end, att.name))
            }
            AttachmentBlock(att.name, body, inlined = true, truncated = r.truncated)
        }
        is InlineAttachment.Skipped -> AttachmentBlock(
            att.name,
            context.getString(
                R.string.attachment_not_inlined,
                head,
                AttachmentLoader.formatBytes(r.sizeBytes),
                context.getString(r.reasonRes)
            ),
            inlined = false
        )
        InlineAttachment.Failed -> AttachmentBlock(
            att.name,
            context.getString(R.string.attachment_read_failed, head),
            inlined = false
        )
    }
}

internal fun parseJsonSafely(json: String): JsonObject = AppJson.parseObject(json)

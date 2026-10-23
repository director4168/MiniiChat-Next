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
 * [reuseMsgId]不为空时会续写那一条助手消息（工具调用后继续生成属于同一轮回复），而不是每条消息开一个新气泡
 */
internal fun ChatViewModel.triggerGeneration(cid: String, reuseMsgId: String? = null) {
    streamingJob?.cancel()
    streamingJob = viewModelScope.launch {
        _isStreaming.value = true
        _streamingConvId.value = cid
        _streamingOverlay.value = null
        // 本次执行是否停在"等用户审批"（true=保留busy态；false=流真正结束，清busy）
        var waitingApproval = false
        try {
            var round = 0
            var reuse = reuseMsgId
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val res = runGenerationRound(cid, reuse) ?: return@launch
                reuse = res.msgId
                if (res.pending.isEmpty()) break
                // 有pending等待用户操作（审批/澄清选择）：保持忙碌态（按键=停止），等用户处理后重启生成
                val hasPending = res.pending.any { it.state == ToolInvocationState.PENDING_APPROVAL }
                _hasPendingTools.value = hasPending
                _pendingConvId.value = cid
                if (!executeAutoApprovedTools(cid, res.pending)) {
                    // 只有确实停在等审批才保留busy；没有待审批说明该结束了
                    waitingApproval = hasPending
                    break
                }
            }
        } finally {
            _streamingOverlay.value = null
            _isStreaming.value = false
            _streamingConvId.value = null
            streamingJob = null
            // 流真正结束（非等审批）时清busy——否则"回复已结束仍显示未结束"（审批后续写走新一轮，会正常清）
            if (!waitingApproval) {
                _hasPendingTools.value = false
                _pendingConvId.value = null
            }
        }
    }
}

private const val MAX_TOOL_ROUNDS = 8
/** 流式期间DataStore节流落库间隔（ms）；toolCalls delta在流末尾统一到达，流结束后的强制落库兜底 */
private const val PERSIST_INTERVAL_MS = 800L

/** 跑一轮流式生成并落库，返回本轮待处理的工具调用；返回null表示无法继续 */
private data class RoundResult(val msgId: String, val pending: List<ToolInvocation>)

private suspend fun ChatViewModel.runGenerationRound(cid: String, reuseMsgId: String?): RoundResult? {
    val conv = store.snapshot().firstOrNull { it.id == cid } ?: return null
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
    /**
     * 把一段文本/思考按"末尾合并"策略追加到parts：
     * - 末尾是同类型段→拼接
     * - 否则→新建一段
     * 不用inline fun+reified（不能在普通函数里定义inline函数），手工两份版本。
     */
    fun appendText(chunk: String) {
        val last = parts.lastOrNull()
        if (last is com.miniichatNext.carter.data.model.MessageSegment.Text) {
            parts[parts.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            parts.add(com.miniichatNext.carter.data.model.MessageSegment.Text(chunk))
        }
    }
    fun appendReasoning(chunk: String) {
        val last = parts.lastOrNull()
        if (last is com.miniichatNext.carter.data.model.MessageSegment.Reasoning) {
            parts[parts.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            parts.add(com.miniichatNext.carter.data.model.MessageSegment.Reasoning(chunk))
        }
    }
    fun mergedParts(): List<com.miniichatNext.carter.data.model.MessageSegment> = parts.toList()

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
                _streamingOverlay.value = assistantMsg.id to mergedContent()
            }
            if (delta.reasoning.isNotEmpty()) {
                reasoning.append(delta.reasoning)
                appendReasoning(delta.reasoning)
            }
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
                    // 工具节点：按出现顺序追加；后续同id的delta只更新参数，不再重复append
                    parts.add(com.miniichatNext.carter.data.model.MessageSegment.Tool(tc.id))
                }
            }

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

    usage?.let { settingsRepo.addTokenUsage(it) }
    generateTitle()
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
 * 所以只要还有任何一个调用停在待审批，就不能继续生成——等用户处理完最后一个再续写
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

/**
 * 是否需要「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）的工具
 * - shell可访问任意路径→需要
 * - BuiltInFileTools的文件类被safeFile重定向到appFilesDir→不需要
 * - 所有MCP工具保守设为需要（不知MCP server实现细节）
 */
private fun toolsRequiringAllFilesAccess(toolName: String): Boolean {
    if (toolName == "execute_shell") return true
    if (com.miniichatNext.carter.data.mcp.BuiltInFileTools.isBuiltIn(toolName)) return false
    return true
}

private fun hasAllFilesAccessPermission(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.os.Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private suspend fun ChatViewModel.executeTool(
    cid: String,
    toolName: String,
    argsJson: String
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    // 工具需要「所有文件访问权限」但用户未授权→设state触发ChatScreen弹窗（ChatViewModelMessage不直接弹）
    if (toolsRequiringAllFilesAccess(toolName) && !hasAllFilesAccessPermission(getApplication())) {
        requestPermission(toolName)
        return@withContext false to getApplication<Application>().getString(R.string.permission_manage_storage_required)
    }
    try {
        if (isWorkspaceTool(toolName)) {
            val workspaceId = workspaceIdFor(cid)
                ?: return@withContext false to "This assistant is not bound to a workspace"
            val res = executeWorkspaceTool(toolName, argsJson, workspaceId, workspaceRepository)
            return@withContext res.ok to res.content.ifBlank { res.error ?: "" }
        }

        if (BuiltInFileTools.isBuiltIn(toolName)) {
            val res = BuiltInFileTools.execute(getApplication(), toolName, argsJson)
            return@withContext res.ok to (if (res.ok) res.content else (res.error ?: "unknown error"))
        }

        val tool = mcpStore.toolsSnapshot().firstOrNull { it.name == toolName && it.serverId.isNotBlank() }
        val server = mcpStore.snapshot().firstOrNull { it.id == tool?.serverId }
            ?: return@withContext false to "No MCP server provides tool $toolName"
        val res = mcpClient.callTool(server, toolName, argsJson)
        res.ok to (if (res.ok) res.content
            else (res.error ?: getApplication<Application>().getString(R.string.common_execution_failed)))
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
        // 澄清工具：必须等用户在卡片里作答，绝不能自动执行续写（否则AI跳过提问直接回答）
        if (inv.toolName == "interactive_clarification") {
            states[inv.callId] = ToolInvocationState.PENDING_APPROVAL
            continue
        }

        val (enabled, requiresApproval) = if (isWorkspaceTool(inv.toolName)) {
            // 工作区工具（202610162134三类归并）：mcpStore全局enabled开关；
            // requiresApproval=类别开关优先→per-tool覆盖→类别默认表
            val perm = perms.firstOrNull {
                it.toolName == inv.toolName && it.serverId == WORKSPACE_SERVER_ID
            }
            val requires = perm?.requireApproval
                ?: resolveWorkspaceToolApprovalByCategory(
                    inv.toolName,
                    workspace?.toolApprovalOverrides().orEmpty(),
                    workspace?.toolApprovalOverrides().orEmpty(),
                )
            (perm?.enabled ?: true) to requires
        } else {
            val perm = perms.firstOrNull { it.toolName == inv.toolName }
            // 默认值与内置工具页UI同源（BuiltInFileTools.defaultRequireApproval），
            // 否则"UI显示需要审批、实际免审批"的双轨不一致
            (perm?.enabled ?: true) to (
                perm?.requireApproval
                    ?: BuiltInFileTools.defaultRequireApproval(inv.toolName)
            )
        }
        if (!enabled || requiresApproval) continue

        states[inv.callId] = ToolInvocationState.RUNNING
        updateToolInvocationState(cid, inv.callId, ToolInvocationState.RUNNING)
        DebugLog.i("Tools", "auto-execute: " + inv.toolName)
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
    if (stillPending) {
        DebugLog.i(
            "Tools",
            "waiting user approval: " + states.count { it.value == ToolInvocationState.PENDING_APPROVAL } + " pending"
        )
    }
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
    viewModelScope.launch {
        val cid = findConvIdForMessage(messageId) ?: return@launch
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
    BuiltInFileTools.schema(getApplication()).forEach { specs += ToolSpec(it.name, it.description, it.inputSchema) }

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

    val wsId = effectiveWorkspaceId(conv, assistant)
    if (!wsId.isNullOrBlank()) {
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

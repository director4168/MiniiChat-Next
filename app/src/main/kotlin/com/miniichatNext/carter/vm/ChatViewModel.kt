/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.vm

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.R
import com.miniichatNext.carter.api.LlmClient
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.avatar.AvatarStorage
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.MessageSegment
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ToolInvocationState
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.skills.SkillStore
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.store.AssistantStore
import com.miniichatNext.carter.data.store.ConversationStore
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.data.tools.BuiltInFileTools
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.data.workspace.WorkspaceRepository
import com.miniichatNext.carter.data.workspace.WorkspaceStore
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    internal var startupApplied = false
    internal val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    internal val skillStore = SkillStore(app)
    internal val userProfileStore = UserProfileStore(app)
    internal val client = LlmClient { usage ->
        runCatching { settingsRepo.addTokenUsage(usage) }
            .onFailure { DebugLog.e("UsageRecorder", "record usage failed", it) }
    }

    /** MCP服务器/工具/权限（纯JDK JSON-RPC客户端，不再依赖MCP SDK与Ktor） */
    internal val mcpStore = McpStore(app)

    /** 多工作区：一个助手绑定一个workspaceId */
    internal val workspaceStore = WorkspaceStore(app)
    internal val workspaceRepository = WorkspaceRepository(app, workspaceStore)

    fun hasRootfsFor(root: String): Boolean = runCatching {
        // 与WorkspaceManager.hasRootfs同源：linux/bin/sh存在即可用（文件直查，不经Repository新API）
        val dir = java.io.File(java.io.File(getApplication<Application>().filesDir, "workspaces"), root)
        java.io.File(dir, "linux/bin/sh").isFile
    }.getOrDefault(false)
    internal val mcpClient = com.miniichatNext.carter.data.mcp.McpClient()

    init {
        BuiltInFileTools.init(
            filesDir = app.filesDir,
            externalFilesDir = runCatching { app.getExternalFilesDir(null) }.getOrNull()
        )
    }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val providers: StateFlow<List<ProviderConfig>> = providerStore.providersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val assistants: StateFlow<List<Assistant>> = assistantStore.assistantsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val skills: StateFlow<List<Skill>> = skillStore.skillsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val userProfile: StateFlow<UserProfile> = userProfileStore.profileFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserProfile())

    // 显示直接使用存储内存快照，结束生成时无需等待第二层stateIn传播。
    val conversations: StateFlow<List<Conversation>> = store.conversationsFlow

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository.workspacesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val mcpSyncJobs = mutableMapOf<String, Job>()
    internal val _syncingMcpIds = MutableStateFlow<Set<String>>(emptySet())
    val syncingMcpIds: StateFlow<Set<String>> = _syncingMcpIds.asStateFlow()

    val mcpServers: StateFlow<List<McpServerConfig>> = mcpStore.serversFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mcpTools: StateFlow<List<McpTool>> = mcpStore.toolsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mcpPerms: StateFlow<List<McpToolPermission>> = mcpStore.permsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val _streamingOverlay = MutableStateFlow<StreamOverlay?>(null)
    val streamingOverlay: StateFlow<StreamOverlay?> = _streamingOverlay.asStateFlow()

    internal val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    internal val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** 正在流式生成的对话id（切到别的对话时发送键不该显示为停止键） */
    internal val _streamingConvId = MutableStateFlow<String?>(null)
    val streamingConvId: StateFlow<String?> = _streamingConvId.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 正在生成候选词的页码集合（驱动候选回复面板的骨架屏） */
    internal val _suggestionsGenerating = MutableStateFlow<Set<String>>(emptySet())
    private val _pendingManageStorage = MutableStateFlow<String?>(null)
    val pendingManageStorage: StateFlow<String?> = _pendingManageStorage.asStateFlow()
    fun requestPermission(toolName: String) { _pendingManageStorage.value = toolName }
    fun dismissPermissionRequest() { _pendingManageStorage.value = null }
    val suggestionsGenerating: StateFlow<Set<String>> = _suggestionsGenerating.asStateFlow()

    /** Toast队列：Channel保证每条提示按序显示、互不覆盖（同轮多个辅助失败依次弹出） */
    /** 存在等待用户操作的工具调用（审批/澄清选择）——此期间输入栏按键保持"停止" */
    internal val _hasPendingTools = MutableStateFlow(false)
    val hasPendingTools: StateFlow<Boolean> = _hasPendingTools.asStateFlow()

    /** 等待审批/澄清的对话id（busy态按对话隔离） */
    internal val _pendingConvId = MutableStateFlow<String?>(null)
    val pendingConvId: StateFlow<String?> = _pendingConvId.asStateFlow()

    internal val _toast = kotlinx.coroutines.channels.Channel<com.miniichatNext.carter.data.model.ToastPayload>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val toast = _toast.receiveAsFlow()

    /** 底部堆叠toast栈：新条目追加到末尾（视觉底部），旧的被顶上去；每条由UI层独立计时移除 */
    internal val _toastItems = MutableStateFlow<List<com.miniichatNext.carter.data.model.ToastItem>>(emptyList())
    val toastItems: StateFlow<List<com.miniichatNext.carter.data.model.ToastItem>> = _toastItems.asStateFlow()
    fun pushToast(item: com.miniichatNext.carter.data.model.ToastItem) {
        _toastItems.value = _toastItems.value + item
    }
    fun removeToast(id: String) {
        _toastItems.value = _toastItems.value.filterNot { it.id == id }
    }

    /** OCR未配置提示只弹一次（进程级防抖，避免每条带图消息都弹） */
    internal var ocrNotConfiguredWarned = false

    /** 辅助模型错误队列（kind: title/suggestion/compress/ocr_not_set/…）：toast按钮变"管理"跳设置 */
    internal val _auxError = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val auxError = _auxError.receiveAsFlow()

    internal val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()

    internal val _compressDialog = MutableStateFlow<Boolean>(false)
    val compressDialog: StateFlow<Boolean> = _compressDialog.asStateFlow()
    internal val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    internal val _tempSkillOverrides = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
    val tempSkillOverrides: StateFlow<Map<String, Map<String, Boolean>>> = _tempSkillOverrides.asStateFlow()

    internal var streamingJob: Job? = null
    internal var taskSession: ChatTaskSession? = null
    internal var stoppingSession: ChatTaskSession? = null
    internal val stoppedToolCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        viewModelScope.launch {
            store.migrateAssistantId(settings.value.activeAssistantId)
            val loadedSkills = skillStore.refresh()
            // 旧助手可能保存的是技能名称；目录技能现在有稳定id，首次加载时迁移引用。
            assistantStore.updateAll { assistants ->
                assistants.map { assistant ->
                    assistant.copy(enabledSkillIds = assistant.enabledSkillIds.map { value ->
                        loadedSkills.firstOrNull { it.id == value || it.name == value }?.id ?: value
                    }.distinct())
                }
            }
            workspaceRepository.checkIntegrity()
        }
        pruneOrphanAvatars()
    }

    /** 配置读取失败就跳过回收，避免把反序列化失败时的默认资料当成真实引用。 */
    internal fun pruneOrphanAvatars() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val loaded = assistantStore.snapshotForCleanup()
                val profile = userProfileStore.snapshotForCleanup()
                val keep = buildSet {
                    loaded.forEach { assistant ->
                        assistant.avatarPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                        assistant.backgroundPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    (profile.avatar as? Avatar.Image)?.path?.takeIf { it.isNotBlank() }?.let { add(it) }
                    store.snapshot().forEach { conversation ->
                        conversation.messages.forEach { message ->
                            message.attachments.forEach { attachment ->
                                if (attachment.uri.startsWith("file:") || attachment.uri.startsWith('/')) add(attachment.uri)
                                if (attachment.originalUri.startsWith("file:") || attachment.originalUri.startsWith('/')) add(attachment.originalUri)
                            }
                        }
                    }
                }
                val removed = AvatarStorage.pruneOrphans(getApplication(), keep, olderThanMs = 24L * 60 * 60 * 1000)
                if (removed > 0) DebugLog.i("AvatarStorage", "pruned $removed temporary crop file(s)")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.w("AvatarStorage", "skip pruning: ${error.message}")
            }
        }
    }

    fun selectConversation(id: String?) {
        _activeId.value = id
    }

    fun newConversation(): String {
        val id = newId()
        _activeId.value = id
        return id
    }

    fun deleteConversation(id: String) {
        // 删除前先切断该对话的生成与pending工具（停止键同款清理），再落盘删除
        if (taskSession?.conversationId == id) stopTaskSession()
        viewModelScope.launch {
            store.delete(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { store.rename(id, title) }
    }

    fun stopStreaming() {
        val session = taskSession ?: return
        if (session.conversationId == _activeId.value) stopTaskSession()
    }

    private companion object {
        /** 停止后等待旧会话收尾的上限：超时就先恢复界面，避免停止键像失灵 */
        const val STOP_SETTLE_TIMEOUT_MS = 2000L
    }

    internal fun stopTaskSession() {
        val session = taskSession ?: return
        val cid = session.conversationId
        val callIds = conversations.value.firstOrNull { it.id == cid }?.messages
            ?.flatMap { it.toolInvocations }?.map { it.callId }?.toSet().orEmpty() + session.toolCallIds()
        stoppedToolCalls.addAll(callIds)
        stoppingSession = session
        session.cancel()
        taskSession = null
        streamingJob = null
        val stoppedNote = getApplication<Application>().getString(R.string.chat_stopped_tool_note)
        // 保留当前气泡直到取消回调把最后一段正文、思考和计量完整提交。
        viewModelScope.launch {
            try {
                // 工具执行可能是不可中断的阻塞调用，等太久会让停止键看起来失灵
                withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MS) { session.awaitStopped() }
                // 超时后旧会话仍可能从NonCancellable收尾，先封住它的后续持久化
                session.settleStop()
                store.updateMessages(cid) { msgs ->
                    msgs.map { msg ->
                        msg.copy(toolInvocations = msg.toolInvocations.map { inv ->
                            if (inv.callId in callIds && (inv.state == ToolInvocationState.EXECUTING ||
                                    inv.state == ToolInvocationState.PENDING_APPROVAL)) {
                                inv.copy(state = ToolInvocationState.CANCELLED, error = stoppedNote)
                            } else inv
                        })
                    }
                }
            } finally {
                // 另一次生成可能已经开始，旧会话只负责自己的收尾。
                if (stoppingSession === session) {
                    if (taskSession == null) {
                        _streamingOverlay.value = null
                        _isStreaming.value = false
                        _hasPendingTools.value = false
                        _streamingConvId.value = null
                        _pendingConvId.value = null
                    }
                    stoppingSession = null
                }
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearToast() { /* Channel消费即出队，无需清理；保留兼容旧调用点 */ }
    fun setError(msg: String) { _error.value = msg }
    fun setToast(msg: String, tone: com.miniichatNext.carter.data.model.ToastTone = com.miniichatNext.carter.data.model.ToastTone.Normal) {
        _toast.trySend(com.miniichatNext.carter.data.model.ToastPayload(msg, tone))
    }
    fun setAuxError(payload: String) { _auxError.trySend(payload) }

    fun requestCompressDialog() { _compressDialog.value = true }
    fun dismissCompressDialog() { _compressDialog.value = false }

    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId }

    fun activeAssistant(): Assistant? =
        assistants.value.firstOrNull { it.id == settings.value.activeAssistantId }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }
}

/**
 * 流式输出覆盖层：只带目标消息的实时正文/思考/节点，
 * 聊天列表按消息id分发给对应行，不再每个token复制整份会话。
 */
@Immutable
data class StreamOverlay(
    val messageId: String,
    val content: String,
    val reasoning: String,
    val parts: List<MessageSegment>,
    val usage: com.miniichatNext.carter.data.model.TokenUsage? = null,
    val generationTimeMs: Long? = null,
    val lastUsage: com.miniichatNext.carter.data.model.TokenUsage? = null,
    val stepCount: Int? = null,
    val finishedAt: Long? = null,
)

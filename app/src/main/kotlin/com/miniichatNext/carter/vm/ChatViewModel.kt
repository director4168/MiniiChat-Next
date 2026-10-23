package com.miniichatNext.carter.vm

import android.app.Application
import com.miniichatNext.carter.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.LlmClient
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.store.AssistantStore
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.store.ConversationStore
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ToolInvocationState
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.skills.SkillStore
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.data.workspace.WorkspaceRepository
import com.miniichatNext.carter.data.workspace.WorkspaceStore
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.avatar.AvatarStorage
import com.miniichatNext.carter.debug.DebugLog

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    internal val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    internal val skillStore = SkillStore(app)
    internal val userProfileStore = UserProfileStore(app)
    internal val client = LlmClient()

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

    val conversations: StateFlow<List<Conversation>> = store.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository.workspacesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mcpServers: StateFlow<List<McpServerConfig>> = mcpStore.serversFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mcpTools: StateFlow<List<McpTool>> = mcpStore.toolsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mcpPerms: StateFlow<List<McpToolPermission>> = mcpStore.permsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val _streamingOverlay = MutableStateFlow<Pair<String, String>?>(null)
    val streamingOverlay: StateFlow<Pair<String, String>?> = _streamingOverlay.asStateFlow()

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

    internal val _toast = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
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

    internal val _tempSkillOverrides = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
    val tempSkillOverrides: StateFlow<Map<String, Map<String, Boolean>>> = _tempSkillOverrides.asStateFlow()

    internal var streamingJob: Job? = null

    init {
        viewModelScope.launch {
            store.migrateAssistantId(settings.value.activeAssistantId)
            skillStore.refresh()
            workspaceRepository.checkIntegrity()
        }
        pruneOrphanAvatars()
    }

    /**
     * 清理不再被引用的头像文件。
     *
     * 两个前提都必须满足，否则会误删用户正在用的头像：
     * 1)助手列表真正加载完（非空）
     * 2)用户资料真正加载完——`userProfile`是StateFlow，初值是空UserProfile()，
     * 直接读.value会拿不到头像路径，把它当孤儿删掉。所以走store.snapshot()。
     * 另外只删24小时前的文件，刚裁剪出来还没写进任何配置的不会被误删。
     */
    internal fun pruneOrphanAvatars() {
        viewModelScope.launch {
            val loaded = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                assistants.first { it.isNotEmpty() }
            } ?: return@launch

            // 关键：从DataStore读一次快照，而不是读StateFlow的当前值
            val profile = runCatching { userProfileStore.snapshot() }
                .getOrElse { return@launch }

            val keep = buildSet {
                loaded.forEach { a ->
                    a.avatarPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    a.backgroundPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                (profile.avatar as? com.miniichatNext.carter.data.avatar.Avatar.Image)
                    ?.path?.takeIf { it.isNotBlank() }?.let { add(it) }
            }

            // 保险：一条路径都没收集到时不做任何删除（宁可留垃圾也不要误删）
            if (keep.isEmpty()) {
                // 正常情况（助手/用户都用emoji头像时keep必然为空）；降为INFO避免误报
                DebugLog.i("AvatarStorage", "no avatar paths to keep; skip pruning")
                return@launch
            }

            val removed = AvatarStorage.pruneOrphans(
                getApplication(),
                keep,
                olderThanMs = 24L * 60 * 60 * 1000
            )
            if (removed > 0) {
                DebugLog.i("AvatarStorage", "pruned $removed orphan avatar file(s)")
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
        if (_activeId.value == id) stopStreaming()
        viewModelScope.launch {
            store.delete(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { store.rename(id, title) }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreaming.value = false
        _hasPendingTools.value = false
        _streamingConvId.value = null
        _pendingConvId.value = null
        // 同步把当前对话最后一条assistant消息里未结束的工具调用标成CANCELLED，
        // 否则UI一直显示"执行中/待审批"的进度条（即使对话已终止）。
        // 注意：不要覆盖SUCCESS/ERROR/REJECTED——这些是历史上正常收尾的结果。
        val cid = _activeId.value ?: return
        val stoppedNote = getApplication<Application>().getString(R.string.chat_stopped_tool_note)
        viewModelScope.launch {
            store.updateMessages(cid) { msgs ->
                if (msgs.isEmpty()) return@updateMessages msgs
                val lastIdx = msgs.indexOfLast { it.role == "assistant" }
                if (lastIdx < 0) return@updateMessages msgs
                val last = msgs[lastIdx]
                if (last.toolInvocations.isEmpty()) return@updateMessages msgs
                val cleaned = last.toolInvocations.map { inv ->
                    when (inv.state) {
                        ToolInvocationState.EXECUTING,
                        ToolInvocationState.PENDING_APPROVAL ->
                            inv.copy(
                                state = ToolInvocationState.CANCELLED,
                                error = inv.error ?: stoppedNote,
                            )
                        else -> inv
                    }
                }
                msgs.toMutableList().also { it[lastIdx] = last.copy(toolInvocations = cleaned) }
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearToast() { /* Channel消费即出队，无需清理；保留兼容旧调用点 */ }
    fun setError(msg: String) { _error.value = msg }
    fun setToast(msg: String) { _toast.trySend(msg) }
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

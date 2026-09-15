package com.miniichatNext.carter.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.LlmClient
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.store.AssistantStore
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.store.ConversationStore
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.skills.SkillStore
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    internal val _streamingOverlay = MutableStateFlow<Pair<String, String>?>(null)
    val streamingOverlay: StateFlow<Pair<String, String>?> = _streamingOverlay.asStateFlow()

    internal val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    internal val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 正在生成候选词的页码集合（驱动候选回复面板的骨架屏） */
    internal val _suggestionsGenerating = MutableStateFlow<Set<Int>>(emptySet())
    val suggestionsGenerating: StateFlow<Set<Int>> = _suggestionsGenerating.asStateFlow()

    internal val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

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
        }
        pruneOrphanAvatars()
    }

    /**
     * 清理不再被引用的头像文件
     * 安全前提：等助手列表真正加载完（非空）后才执行，且只删24小时前的文件，避免把正在使用的头像或刚裁好还没保存的文件删掉
     */
    internal fun pruneOrphanAvatars() {
        viewModelScope.launch {
            val loaded = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                assistants.first { it.isNotEmpty() }
            } ?: return@launch
            val keep = buildSet {
                loaded.forEach { a ->
                    a.avatarPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    a.backgroundPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                (userProfile.value.avatar as? com.miniichatNext.carter.data.avatar.Avatar.Image)
                    ?.path?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val removed = com.miniichatNext.carter.data.avatar.AvatarStorage.pruneOrphans(
                getApplication(),
                keep,
                olderThanMs = 24L * 60 * 60 * 1000
            )
            if (removed > 0) {
                com.miniichatNext.carter.debug.DebugLog.i(
                    "AvatarStorage", "pruned $removed orphan avatar file(s)"
                )
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
    }

    fun clearError() { _error.value = null }
    fun clearToast() { _toast.value = null }
    fun setError(msg: String) { _error.value = msg }
    fun setToast(msg: String) { _toast.value = msg }

    fun requestCompressDialog() { _compressDialog.value = true }
    fun dismissCompressDialog() { _compressDialog.value = false }

    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId }

    fun activeAssistant(): Assistant? =
        assistants.value.firstOrNull { it.id == settings.value.activeAssistantId }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    // ---- Auxiliary feature model resolution ----

    /** 解析辅助功能模型引用（"providerId::modelId"或单独的模型ID） */
}

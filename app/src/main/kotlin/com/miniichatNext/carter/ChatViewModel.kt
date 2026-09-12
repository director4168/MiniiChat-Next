package com.miniichatNext.carter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.LlmClient
import com.miniichatNext.carter.data.AppSettings
import com.miniichatNext.carter.data.Assistant
import com.miniichatNext.carter.data.AssistantStore
import com.miniichatNext.carter.data.Conversation
import com.miniichatNext.carter.data.ConversationStore
import com.miniichatNext.carter.data.Message
import com.miniichatNext.carter.data.ModelConfig
import com.miniichatNext.carter.data.ProviderConfig
import com.miniichatNext.carter.data.ProviderStore
import com.miniichatNext.carter.data.SettingsRepository
import com.miniichatNext.carter.data.Skills.Skill
import com.miniichatNext.carter.data.Skills.SkillStore
import com.miniichatNext.carter.data.TokenUsage
import com.miniichatNext.carter.data.UserProfile
import com.miniichatNext.carter.data.UserProfileStore
import com.miniichatNext.carter.util.PromptVars
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    private val skillStore = SkillStore(app)
    private val userProfileStore = UserProfileStore(app)
    private val client = LlmClient()

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

    private val _streamingOverlay = MutableStateFlow<Pair<String, String>?>(null)
    val streamingOverlay: StateFlow<Pair<String, String>?> = _streamingOverlay.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()

    private val _compressDialog = MutableStateFlow<Boolean>(false)
    val compressDialog: StateFlow<Boolean> = _compressDialog.asStateFlow()

    private val _tempSkillOverrides = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
    val tempSkillOverrides: StateFlow<Map<String, Map<String, Boolean>>> = _tempSkillOverrides.asStateFlow()

    private var streamingJob: Job? = null

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
    private fun pruneOrphanAvatars() {
        viewModelScope.launch {
            val loaded = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                assistants.first { it.isNotEmpty() }
            } ?: return@launch
            val keep = buildSet {
                loaded.forEach { a ->
                    a.avatarPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    a.backgroundPath?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                (userProfile.value.avatar as? com.miniichatNext.carter.data.Avatar.Image)
                    ?.path?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val removed = com.miniichatNext.carter.util.AvatarStorage.pruneOrphans(
                getApplication(),
                keep,
                olderThanMs = 24L * 60 * 60 * 1000
            )
            if (removed > 0) {
                com.miniichatNext.carter.Debug.DebugLog.i(
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

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = model) }
        }
    }

    fun selectAssistant(id: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeAssistantId = id) }
            val convForNew = store.forAssistant(id)
            _activeId.value = convForNew.maxByOrNull { it.updatedAt }?.id
        }
    }

    fun upsertAssistant(a: Assistant) {
        viewModelScope.launch { assistantStore.upsert(a) }
    }

    fun deleteAssistant(id: String) {
        viewModelScope.launch {
            assistantStore.delete(id)
            if (settings.value.activeAssistantId == id) {
                val remaining = assistantStore.snapshot()
                val next = remaining.firstOrNull()?.id ?: "default"
                settingsRepo.update { it.copy(activeAssistantId = next) }
                _activeId.value = null
            }
        }
    }

    fun upsertProvider(p: ProviderConfig) {
        viewModelScope.launch {
            providerStore.upsert(p)
            val all = providerStore.snapshot()
            if (settings.value.activeProviderId.isBlank() && all.isNotEmpty()) {
                val target = all.firstOrNull { it.id == p.id } ?: all.first()
                val firstModel = target.modelIds().firstOrNull() ?: ""
                settingsRepo.update {
                    it.copy(activeProviderId = target.id, activeModel = firstModel)
                }
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerStore.delete(id)
            if (settings.value.activeProviderId == id) {
                val remaining = providerStore.snapshot()
                val nextProvider = remaining.firstOrNull()
                settingsRepo.update {
                    it.copy(
                        activeProviderId = nextProvider?.id ?: "",
                        activeModel = nextProvider?.modelIds()?.firstOrNull() ?: ""
                    )
                }
            }
        }
    }

    fun fetchModels(providerId: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            _fetchingModelsFor.value = providerId
            try {
                val models = client.listModels(provider)
                if (models.isEmpty()) {
                    _toast.value = getApplication<Application>()
                        .getString(com.miniichatNext.carter.R.string.models_none_returned)
                } else {
                    val existingIds = provider.modelIds().toSet()
                    val newConfigs = models.filter { it !in existingIds }
                        .map { ModelConfig(modelId = it, displayName = it) }
                    val updated = provider.copy(models = provider.models + newConfigs)
                    providerStore.upsert(updated)
                    _toast.value = getApplication<Application>()
                        .getString(
                            com.miniichatNext.carter.R.string.models_fetched,
                            models.size
                        )
                }
            } catch (e: Exception) {
                _error.value = getApplication<Application>()
                    .getString(
                        com.miniichatNext.carter.R.string.error_fetch_models,
                        e.message ?: ""
                    )
            } finally {
                _fetchingModelsFor.value = null
            }
        }
    }

    fun addManualModel(providerId: String, model: String) {
        viewModelScope.launch {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return@launch
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            if (provider.modelIds().contains(trimmed)) return@launch
            val updated = provider.copy(
                models = provider.models + ModelConfig(modelId = trimmed, displayName = trimmed)
            )
            providerStore.upsert(updated)
        }
    }

    fun removeModel(providerId: String, model: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(
                models = provider.models.filterNot { it.modelId == model || it.displayName == model }
            )
            providerStore.upsert(updated)
            if (settings.value.activeProviderId == providerId && settings.value.activeModel == model) {
                settingsRepo.update {
                    it.copy(activeModel = updated.modelIds().firstOrNull() ?: "")
                }
            }
        }
    }

    fun updateUserProfile(transform: (UserProfile) -> UserProfile) {
        viewModelScope.launch { userProfileStore.update(transform) }
    }

    fun saveSkill(skill: Skill) {
        viewModelScope.launch { skillStore.upsert(skill) }
    }

    fun saveSkillFiles(name: String, files: Map<String, ByteArray>) {
        viewModelScope.launch {
            if (!skillStore.saveSkillFiles(name, files)) {
                _error.value = getApplication<Application>().getString(com.miniichatNext.carter.R.string.skill_import_failed_empty)
            }
        }
    }

    fun deleteSkill(id: String) {
        viewModelScope.launch {
            val skill = skillStore.snapshot().firstOrNull { it.id == id }
                ?: skillStore.snapshot().firstOrNull { it.name == id }
            val canonicalId = skill?.id ?: id
            val canonicalName = skill?.name ?: id

            skillStore.delete(canonicalId)

            val snapshot = assistantStore.snapshot()
            val cleaned = snapshot.map { a ->
                val pruned = a.enabledSkillIds.filterNot { it == canonicalId || it == canonicalName }
                if (pruned.size != a.enabledSkillIds.size) a.copy(enabledSkillIds = pruned) else a
            }
            if (cleaned != snapshot) assistantStore.save(cleaned)
        }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { skillStore.setEnabled(id, enabled) }
    }

    fun toggleAssistantSkill(assistantId: String, skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            val list = assistantStore.snapshot().toMutableList()
            val idx = list.indexOfFirst { it.id == assistantId }
            if (idx >= 0) {
                val current = list[idx].enabledSkillIds
                val next = if (enabled) (current + skillId).distinct() else current - skillId
                list[idx] = list[idx].copy(enabledSkillIds = next)
                assistantStore.save(list)
            }
        }
    }

        fun toggleTemporarySkill(conversationId: String, skillId: String, enabled: Boolean) {
            val perConv = _tempSkillOverrides.value[conversationId] ?: emptyMap()
            val next = perConv + (skillId to enabled)
            _tempSkillOverrides.value = _tempSkillOverrides.value + (conversationId to next)
        }

        fun effectiveSkillIdsFlow(conversationId: String?): Flow<Set<String>> =
            combine(
                conversations,
                _tempSkillOverrides,
                assistants,
                settings,
            ) { convs, overrides, assistantList, s ->
                val conv = conversationId?.let { cid -> convs.firstOrNull { it.id == cid } }
                val assistantId = conv?.assistantId ?: s.activeAssistantId
                val assistant = assistantList.firstOrNull { it.id == assistantId }
                    ?: return@combine emptySet()
                val base = assistant.enabledSkillIds.toSet()
                val perConv = conversationId?.let { cid -> overrides[cid] } ?: emptyMap()
                var result = base
                for ((skillId, enabled) in perConv) {
                    result = if (enabled) result + skillId else result - skillId
                }
                result
            }

        fun effectiveSkillIds(conversationId: String?): Set<String> {
            val conv = conversationId?.let { cid -> conversations.value.firstOrNull { it.id == cid } }
            val assistantId = conv?.assistantId ?: settings.value.activeAssistantId
            val assistant = assistants.value.firstOrNull { it.id == assistantId } ?: return emptySet()
            val base = assistant.enabledSkillIds.toSet()
            val overrides = conversationId?.let { cid -> _tempSkillOverrides.value[cid] } ?: emptyMap()
            var result = base
            for ((skillId, enabled) in overrides) {
                result = if (enabled) result + skillId else result - skillId
            }
            return result
        }

    private suspend fun activeEnabledSkills(assistant: Assistant?): List<Skill> {
        if (assistant == null) return emptyList()
        val ids = effectiveSkillIds(_activeId.value)
        if (ids.isEmpty()) return emptyList()
        return skillStore.snapshot().filter { it.id in ids && it.enabled }
    }

    fun sendMessage(text: String, attachments: List<com.miniichatNext.carter.data.Attachment> = emptyList()) {
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

        com.miniichatNext.carter.Debug.DebugLog.i(
            "ChatVM",
            "sendMessage chars=${trimmed.length} atts=${attachments.size} " +
                "provider=${provider.name} model=$model stream=${current.stream} " +
                "assistant=$activeAssistantId temp=$temperature"
        )

        viewModelScope.launch {
            val activeId = _activeId.value ?: newId().also { _activeId.value = it }
            val enabledSkills = activeEnabledSkills(assistant)
            com.miniichatNext.carter.Debug.DebugLog.i(
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
                            com.miniichatNext.carter.Debug.DebugLog.e(
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

    private suspend fun appendAssistant(convId: String, msgId: String, content: String, usage: TokenUsage?) {
        store.updateMessages(convId) { msgs ->
            msgs.map {
                if (it.id == msgId) it.copy(content = content, usage = usage ?: it.usage) else it
            }
        }
    }

    fun regenerate() {
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

    fun continueGenerating() {
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
                            com.miniichatNext.carter.Debug.DebugLog.e(
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

    fun regenerateFrom(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val target = msgs[idx]
            if (target.role != "user") return@launch
            val trimmed = msgs.subList(0, idx + 1)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(target.content, target.attachments)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            store.updateMessages(convId) { it.filterNot { m -> m.id == messageId } }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            store.updateMessages(convId) { msgs ->
                msgs.map { if (it.id == messageId) it.copy(content = newContent) else it }
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    // ---- Auxiliary feature model resolution ----

    /** 解析辅助功能模型引用（"providerId::modelId"或单独的模型ID） */
    private fun resolveModelRef(ref: String): Pair<ProviderConfig, String>? {
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
    private suspend fun runAuxCompletion(
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
    fun generateTitle() {
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
    fun generateSuggestions(page: Int) {
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
        viewModelScope.launch {
            val locale = java.util.Locale.getDefault().displayName
            val content = conv.messages.takeLast(8).joinToString("\n\n") { m ->
                "${m.role}: ${m.content.take(500)}"
            }
            val prompt = buildString {
                append("I will give you some chat content. Act as the User and give exactly 4 ")
                append("appropriate reply suggestions, separated by newlines.\n")
                append("Each suggestion must be 10 characters or fewer.\n")
                append("Imitate the user's style. Do not add formatting or list markers.\n")
                append("Use this language: ").append(locale).append("\n")
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
        }
    }

    /** 对话压缩 */
    fun compressContext(targetTokens: Int, keepRecentMessages: Int, additionalPrompt: String) {
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
}

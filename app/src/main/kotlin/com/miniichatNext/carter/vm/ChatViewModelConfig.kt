package com.miniichatNext.carter.vm

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun ChatViewModel.selectModel(providerId: String, model: String) {
    viewModelScope.launch {
        settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = model) }
    }
}

internal fun ChatViewModel.selectAssistant(id: String) {
    viewModelScope.launch {
        settingsRepo.update { it.copy(activeAssistantId = id) }
        val convForNew = store.forAssistant(id)
        _activeId.value = convForNew.maxByOrNull { it.updatedAt }?.id
    }
}

internal fun ChatViewModel.upsertAssistant(a: Assistant) {
    viewModelScope.launch { assistantStore.upsert(a) }
}

internal fun ChatViewModel.deleteAssistant(id: String) {
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

internal fun ChatViewModel.upsertProvider(p: ProviderConfig) {
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

internal fun ChatViewModel.deleteProvider(id: String) {
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

internal fun ChatViewModel.fetchModels(providerId: String) {
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

internal fun ChatViewModel.addManualModel(providerId: String, model: String) {
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

internal fun ChatViewModel.removeModel(providerId: String, model: String) {
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

internal fun ChatViewModel.updateUserProfile(transform: (UserProfile) -> UserProfile) {
    viewModelScope.launch { userProfileStore.update(transform) }
}

internal fun ChatViewModel.saveSkill(skill: Skill) {
    viewModelScope.launch { skillStore.upsert(skill) }
}

internal fun ChatViewModel.saveSkillFiles(name: String, files: Map<String, ByteArray>) {
    viewModelScope.launch {
        if (!skillStore.saveSkillFiles(name, files)) {
            _error.value = getApplication<Application>().getString(com.miniichatNext.carter.R.string.skill_import_failed_empty)
        }
    }
}

internal fun ChatViewModel.deleteSkill(id: String) {
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

internal fun ChatViewModel.setSkillEnabled(id: String, enabled: Boolean) {
    viewModelScope.launch { skillStore.setEnabled(id, enabled) }
}

internal fun ChatViewModel.toggleAssistantSkill(assistantId: String, skillId: String, enabled: Boolean) {
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

internal fun ChatViewModel.toggleTemporarySkill(conversationId: String, skillId: String, enabled: Boolean) {
    val perConv = _tempSkillOverrides.value[conversationId] ?: emptyMap()
    val next = perConv + (skillId to enabled)
    _tempSkillOverrides.value = _tempSkillOverrides.value + (conversationId to next)
}

internal fun ChatViewModel.effectiveSkillIdsFlow(conversationId: String?): Flow<Set<String>> =
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

internal fun ChatViewModel.effectiveSkillIds(conversationId: String?): Set<String> {
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

internal suspend fun ChatViewModel.activeEnabledSkills(assistant: Assistant?): List<Skill> {
    if (assistant == null) return emptyList()
    val ids = effectiveSkillIds(_activeId.value)
    if (ids.isEmpty()) return emptyList()
    return skillStore.snapshot().filter { it.id in ids && it.enabled }
}

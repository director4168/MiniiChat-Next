package com.miniichatNext.carter.vm

import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderOverride
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.launch

/**
 * 对话实际生效的模型：对话覆盖>助手preferred>全局默认。
 *
 * 全链统一用这一个解析（聊天页选择器显示/写入、发送、续写、UI展示），
 * 返回null=没配好（provider不存在或model为空），调用方各自处理错误提示。
 */
fun effectiveConversationModel(
    conv: Conversation?,
    assistant: Assistant?,
    settings: AppSettings,
    providers: List<ProviderConfig>,
): Pair<ProviderConfig, String>? {
    val cp = conv?.modelProviderId?.takeIf { it.isNotBlank() }
    val cm = conv?.modelId?.takeIf { it.isNotBlank() }
    if (cp != null && cm != null) {
        providers.firstOrNull { it.id == cp }?.let { return it to cm }
    }
    val ap = assistant?.preferredProviderId?.takeIf { it.isNotBlank() }
        ?.let { id -> providers.firstOrNull { it.id == id } }
    val am = assistant?.preferredModel?.takeIf { it.isNotBlank() }
    if (ap != null && am != null) return ap to am
    val dp = providers.firstOrNull { it.id == settings.activeProviderId }
    if (dp != null && settings.activeModel.isNotBlank()) return dp to settings.activeModel
    return null
}

/** 对话级覆盖优先，其次助手配置 */
fun effectiveWorkspaceId(conv: Conversation?, assistant: Assistant?): String? =
    if (conv?.toolWorkspaceOverridden == true) conv.toolWorkspaceId else assistant?.workspaceId

fun effectiveMcpServerIds(conv: Conversation?, assistant: Assistant?): Set<String> =
    if (conv?.toolMcpOverridden == true) conv.toolMcpEnabled else assistant?.mcpServerIds.orEmpty()

internal fun Conversation.providerOverride(providerId: String): ProviderOverride =
    providerOverrides[providerId] ?: ProviderOverride()

private suspend fun ChatViewModel.updateConversationSettings(
    convId: String,
    transform: (Conversation) -> Conversation,
) {
    val assistantId = settings.value.activeAssistantId
    store.updateConversation(
        convId,
        create = { Conversation(id = convId, title = "", assistantId = assistantId) },
        transform = transform,
    )
}

internal fun ChatViewModel.setConversationWorkspace(convId: String, workspaceId: String?) {
    viewModelScope.launch {
        updateConversationSettings(convId) {
            it.copy(toolWorkspaceOverridden = true, toolWorkspaceId = workspaceId)
        }
    }
}

internal fun ChatViewModel.setConversationProviderOverride(
    convId: String,
    providerId: String,
    transform: (ProviderOverride) -> ProviderOverride,
) {
    if (providerId.isBlank()) return
    viewModelScope.launch {
        updateConversationSettings(convId) {
            it.copy(providerOverrides = it.providerOverrides + (providerId to transform(it.providerOverride(providerId))))
        }
    }
}

internal fun ChatViewModel.clearConversationProviderOverride(convId: String, providerId: String) {
    if (providerId.isBlank()) return
    viewModelScope.launch {
        updateConversationSettings(convId) { it.copy(providerOverrides = it.providerOverrides - providerId) }
    }
}

internal fun ChatViewModel.setConversationThinkingLevel(cid: String, level: ThinkingLevel?) {
    viewModelScope.launch {
        updateConversationSettings(cid) { it.copy(thinkingLevel = level?.name) }
    }
}

internal fun ChatViewModel.ensureActiveConversationId(): String =
    _activeId.value ?: newId().also { _activeId.value = it }

internal fun ChatViewModel.setConversationMcpServer(convId: String, serverId: String, enabled: Boolean) {
    viewModelScope.launch {
        updateConversationSettings(convId) { conv ->
            val assistant = assistants.value.firstOrNull { it.id == conv.assistantId }
            val base = effectiveMcpServerIds(conv, assistant)
            conv.copy(toolMcpOverridden = true, toolMcpEnabled = if (enabled) base + serverId else base - serverId)
        }
    }
}

internal fun ChatViewModel.clearConversationToolOverrides(convId: String) {
    viewModelScope.launch {
        updateConversationSettings(convId) {
            it.copy(toolWorkspaceOverridden = false, toolWorkspaceId = null,
                toolMcpOverridden = false, toolMcpEnabled = emptySet())
        }
    }
}

internal fun ChatViewModel.setConversationModel(convId: String, providerId: String, modelId: String) {
    if (providerId.isBlank() || modelId.isBlank()) return
    viewModelScope.launch {
        updateConversationSettings(convId) { it.copy(modelProviderId = providerId, modelId = modelId) }
    }
}

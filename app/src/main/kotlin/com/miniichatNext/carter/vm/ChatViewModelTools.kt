package com.miniichatNext.carter.vm

import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.ProviderOverride
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.launch

/** 对话级覆盖优先，其次助手配置 */
fun effectiveWorkspaceId(conv: Conversation?, assistant: Assistant?): String? =
    if (conv?.toolWorkspaceOverridden == true) conv.toolWorkspaceId else assistant?.workspaceId

fun effectiveMcpServerIds(conv: Conversation?, assistant: Assistant?): Set<String> =
    if (conv?.toolMcpOverridden == true) conv.toolMcpEnabled else assistant?.mcpServerIds.orEmpty()

/** 绑定/解绑当前对话使用的工作区（null=不使用工作区） */
internal fun ChatViewModel.setConversationWorkspace(convId: String, workspaceId: String?) {
    viewModelScope.launch {
        val conv = ensureConversationEntity(convId)
        store.upsert(
            conv.copy(
                toolWorkspaceOverridden = true,
                toolWorkspaceId = workspaceId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/** 该对话对某个服务商的覆盖（没记录就返回全null=全部跟随服务商） */
internal fun Conversation.providerOverride(providerId: String): ProviderOverride =
    providerOverrides[providerId] ?: ProviderOverride()

/**
 * 写入对话级按服务商覆盖
 *
 * 作用域只影响本对话；且以服务商为key，所以对该服务商下的所有模型生效，换到别的服务商的模型就不生效了。传null即清掉该字段回到跟随服务商
 */
internal fun ChatViewModel.setConversationProviderOverride(
    convId: String,
    providerId: String,
    transform: (ProviderOverride) -> ProviderOverride,
) {
    if (providerId.isBlank()) return
    viewModelScope.launch {
        val conv = ensureConversationEntity(convId)
        val next = transform(conv.providerOverride(providerId))
        store.upsert(
            conv.copy(
                providerOverrides = conv.providerOverrides + (providerId to next),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/** 清掉本对话对某个服务商的全部覆盖 */
internal fun ChatViewModel.clearConversationProviderOverride(convId: String, providerId: String) {
    if (providerId.isBlank()) return
    viewModelScope.launch {
        val conv = ensureConversationEntity(convId)
        store.upsert(
            conv.copy(
                providerOverrides = conv.providerOverrides - providerId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/**
 * 写对话级思考等级（null=跟随服务商/自动）
 *
 * 注意：思考等级只存在于Conversation这一级，服务商/模型都不再有这条设置
 * 这样切换服务商/模型时思考等级保持不变（属于对话而非配置）
 */
internal fun ChatViewModel.setConversationThinkingLevel(cid: String, level: ThinkingLevel?) {
    viewModelScope.launch {
        val conv = ensureConversationEntity(cid)
        if (conv.thinkingLevel == level?.name) return@launch
        store.upsert(
            conv.copy(
                thinkingLevel = level?.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/**
 * 当前对话id，还没有就先生成一个
 *
 * 聊天页在还没发过消息时也能改设置，之前UI直接读_activeId，为null时所有setter都被?.let{}静默跳过，表现就是开关点了没反应
 */
internal fun ChatViewModel.ensureActiveConversationId(): String =
    _activeId.value ?: newId().also { _activeId.value = it }

/** 开关当前对话可用的MCP服务器 */
internal fun ChatViewModel.setConversationMcpServer(convId: String, serverId: String, enabled: Boolean) {
    viewModelScope.launch {
        val conv = ensureConversationEntity(convId)
        val assistant = assistants.value.firstOrNull { it.id == conv.assistantId }
        val base = effectiveMcpServerIds(conv, assistant)
        val next = if (enabled) base + serverId else base - serverId
        store.upsert(
            conv.copy(
                toolMcpOverridden = true,
                toolMcpEnabled = next,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/** 清空对话级覆盖，回到跟随助手 */
internal fun ChatViewModel.clearConversationToolOverrides(convId: String) {
    viewModelScope.launch {
        val conv = ensureConversationEntity(convId)
        store.upsert(
            conv.copy(
                toolWorkspaceOverridden = false,
                toolWorkspaceId = null,
                toolMcpOverridden = false,
                toolMcpEnabled = emptySet(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/** 会话可能还没落库（例如刚新建、还没发消息），这里按需建一条 */
private suspend fun ChatViewModel.ensureConversationEntity(convId: String): Conversation {
    store.snapshot().firstOrNull { it.id == convId }?.let { return it }
    val fresh = Conversation(
        id = convId,
        title = "",
        assistantId = settings.value.activeAssistantId
    )
    store.upsert(fresh)
    return fresh
}

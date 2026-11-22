/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.vm

import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.store.COMPRESS_MODEL_CONVERSATION

internal suspend fun ChatViewModel.resolveImageGenerationModel(cid: String): Pair<ProviderConfig, String>? {
    val ref = settings.value.imageGenerationModel
    if (ref == COMPRESS_MODEL_CONVERSATION) {
        val conv = store.snapshot().firstOrNull { it.id == cid }
        val assistant = assistants.value.firstOrNull { it.id == conv?.assistantId } ?: activeAssistant()
        return effectiveConversationModel(conv, assistant, settings.value, providers.value)
    }
    return resolveModelRef(ref)
}

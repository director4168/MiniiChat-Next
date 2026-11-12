package com.miniichatNext.carter.ui.chat

import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.vm.StreamOverlay

/** 列表项重新进入组合时优先沿用同一条消息的实时快照，屏蔽落库延迟和其他对话的快照。 */
internal fun Message.withStreamingSnapshot(overlay: StreamOverlay?): Message {
    if (overlay == null || overlay.messageId != id) return this
    // 落库与快照交接时，旧帧也可能短暂落在实时帧后面，正文和思考都保持单向追加。
    if (content.length > overlay.content.length && content.startsWith(overlay.content)) return this
    val reasoning = reasoningContent.orEmpty()
    if (overlay.reasoning.isNotBlank() && reasoning.length > overlay.reasoning.length && reasoning.startsWith(overlay.reasoning)) return this
    return copy(
        content = overlay.content,
        reasoningContent = overlay.reasoning
            .ifBlank { reasoningContent.orEmpty() }
            .takeIf { it.isNotBlank() },
        orderedParts = overlay.parts.ifEmpty { orderedParts },
        usage = overlay.usage,
        generationTimeMs = overlay.generationTimeMs,
        lastUsage = overlay.lastUsage,
        stepCount = overlay.stepCount,
        finishedAt = overlay.finishedAt,
    )
}

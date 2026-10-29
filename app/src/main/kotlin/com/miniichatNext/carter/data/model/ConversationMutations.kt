package com.miniichatNext.carter.data.model

/** 仅替换仍与压缩请求一致的前缀，尾部保留提交时的最新消息。 */
internal fun mergeCompressedMessages(
    current: List<Message>,
    source: List<Message>,
    summary: Message,
): List<Message>? {
    if (source.isEmpty() || current.size < source.size) return null
    if (current.take(source.size) != source) return null
    return listOf(summary) + current.drop(source.size)
}

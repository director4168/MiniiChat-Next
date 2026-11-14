/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.chat

/** 正序消息索引与数据顺序一致，尾部占位项不参与消息翻页。 */
internal object ChatListOrder {
    const val BOTTOM_KEY = "chat-scroll-bottom"

    fun messageIndex(listIndex: Int, messageCount: Int): Int? =
        listIndex.takeIf { it in 0 until messageCount }

    fun jumpTarget(action: Int, currentListIndex: Int, messageCount: Int, boundaryHidden: Boolean = false): Int? {
        if (messageCount <= 0 || action !in 0..3) return null
        val last = messageCount - 1
        val current = currentListIndex.coerceIn(0, last)
        return when (action) {
            0 -> 0
            1 -> if (boundaryHidden) current else (current - 1).coerceAtLeast(0)
            2 -> (current + 1).coerceAtMost(last)
            else -> last
        }
    }
}

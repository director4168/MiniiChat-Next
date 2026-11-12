package com.miniichatNext.carter.ui.chat

/** UI仍按时间顺序阅读；LazyColumn用最新消息作为第0项，内容增高时原生锚住底部。 */
internal object ChatListOrder {
    fun messageIndex(listIndex: Int, messageCount: Int): Int? =
        if (listIndex in 0 until messageCount) messageCount - 1 - listIndex else null

    fun jumpTarget(action: Int, currentListIndex: Int, messageCount: Int, boundaryHidden: Boolean = false): Int? {
        if (messageCount <= 0 || action !in 0..3) return null
        val last = messageCount - 1
        val current = currentListIndex.coerceIn(0, last)
        return when (action) {
            0 -> last
            1 -> if (boundaryHidden) current else (current + 1).coerceAtMost(last)
            2 -> if (boundaryHidden) current else (current - 1).coerceAtLeast(0)
            else -> 0
        }
    }

    // reverseLayout的offset从视口底部计，正滚动向旧消息移动。
    fun boundaryHidden(action: Int, itemOffset: Int, itemSize: Int, viewportSize: Int): Boolean = when (action) {
        1 -> itemOffset.toLong() + itemSize > viewportSize.toLong() + 1
        2 -> itemOffset < -1
        else -> false
    }

    fun jumpOffset(action: Int, messageHeight: Int, viewportHeight: Int): Int =
        if (action == 0 || action == 1) messageHeight - viewportHeight.coerceAtLeast(0) else 0

    fun jumpDistance(action: Int, itemOffset: Int, itemSize: Int, viewportSize: Int): Float =
        itemOffset.toFloat() + jumpOffset(action, itemSize, viewportSize)

    /** 相邻目标尚未参与测量时先移入1px，再在同一次滚动作用域内对齐。 */
    fun revealDistance(action: Int, itemOffset: Int, itemSize: Int, viewportSize: Int, spacing: Int): Float =
        if (action == 1) {
            (itemOffset.toFloat() + itemSize + spacing - viewportSize + 1).coerceAtLeast(1f)
        } else {
            (itemOffset.toFloat() - spacing - 1).coerceAtMost(-1f)
        }
}

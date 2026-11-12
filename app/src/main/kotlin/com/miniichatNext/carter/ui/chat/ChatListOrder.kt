/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
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
            2 -> (current - 1).coerceAtLeast(0)
            else -> 0
        }
    }

    /**
     * 消息可见区域顶边到消息区顶部的距离。
     * 反向列表的offset从底部计，直接比大小会挑错消息。
     */
    fun visualTop(itemOffset: Int, itemSize: Int, contentBottom: Int, reverseLayout: Boolean): Int =
        if (reverseLayout) contentBottom - itemOffset - itemSize else itemOffset

    /** 占据消息区顶边的消息；没有正好跨越顶边时取最靠近顶边的一条。 */
    fun topOwnerIndex(visualTops: List<Pair<Int, Int>>, itemSizes: List<Int>): Int? {
        if (visualTops.isEmpty()) return null
        val spanning = visualTops.filterIndexed { i, (_, top) -> top <= 0 && top + itemSizes[i] > 0 }
        if (spanning.isNotEmpty()) return spanning.maxByOrNull { it.second }?.first
        return visualTops.minByOrNull { kotlin.math.abs(it.second) }?.first
    }

    // reverseLayout的offset从视口底部计，正滚动向旧消息移动。
    fun boundaryHidden(action: Int, itemOffset: Int, itemSize: Int, viewportSize: Int): Boolean = when (action) {
        1 -> itemOffset.toLong() + itemSize > viewportSize.toLong() + 1
        else -> false
    }

    fun jumpOffset(action: Int, messageHeight: Int, viewportHeight: Int): Int =
        if (action in 0..2) messageHeight - viewportHeight.coerceAtLeast(0) else 0

}

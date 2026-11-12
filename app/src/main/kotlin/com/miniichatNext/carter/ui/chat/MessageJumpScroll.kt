/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState

/** 沿用rikkahub的索引跳转，反向列表仅调整目标索引及首尾偏移。 */
internal suspend fun LazyListState.jumpMessage(action: Int, messageCount: Int) {
    if (messageCount <= 0 || action !in 0..3) return
    // 先结束惯性与旧动画，再读取布局，避免用已经过期的位置计算跳转。
    stopScroll()
    val initial = layoutInfo
    val count = minOf(messageCount, initial.totalItemsCount)
    if (count <= 0) return
    if (action == 3) {
        scrollToItem(0, 0)
        return
    }

    val contentBottom = (initial.viewportEndOffset - initial.afterContentPadding).coerceAtLeast(0)
    val viewport = contentBottom
    val visible = initial.visibleItemsInfo.filter {
        it.index in 0 until count && it.offset.toLong() + it.size > 0 && it.offset < viewport
    }
    val target = if (action == 0) count - 1 else {
        val current = if (action == 1) {
            visible.maxByOrNull { it.index }
        } else {
            val tops = visible.map {
                it.index to ChatListOrder.visualTop(it.offset, it.size, contentBottom, initial.reverseLayout)
            }
            val owner = ChatListOrder.topOwnerIndex(tops, visible.map { it.size }) ?: return
            visible.firstOrNull { it.index == owner }
        }
        current ?: return
        if (action == 2 && current.index == 0) return
        ChatListOrder.jumpTarget(
            action, current.index, count,
            ChatListOrder.boundaryHidden(action, current.offset, current.size, viewport),
        ) ?: return
    }
    val measured = visible.firstOrNull { it.index == target }
    if ((action == 1 || action == 2) && measured != null) {
        animateScrollToItem(target, ChatListOrder.jumpOffset(action, measured.size, viewport))
    } else {
        // 屏外消息先由LazyColumn同步测量，再以最新高度设置最终偏移；不播放中间动画。
        scrollToItem(target, 0)
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return
        val size = (info.viewportEndOffset - info.afterContentPadding).coerceAtLeast(0)
        scrollToItem(target, ChatListOrder.jumpOffset(action, item.size, size))
    }
}

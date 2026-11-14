/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState

/** 沿用rikkahub的正序索引跳转，单箭头定位消息开头，双下定位尾部占位项。 */
internal suspend fun LazyListState.jumpMessage(action: Int, messageCount: Int) {
    if (messageCount <= 0 || action !in 0..3) return
    stopScroll()
    val initial = layoutInfo
    val count = minOf(messageCount, initial.totalItemsCount)
    if (count <= 0) return
    if (action == 3) {
        scrollToItem(initial.totalItemsCount - 1)
        return
    }
    if (action == 0) {
        scrollToItem(0)
        return
    }
    val current = initial.visibleItemsInfo.firstOrNull {
        it.index in 0 until count && it.offset.toLong() + it.size > 0
    } ?: return
    if (action == 2 && current.index == count - 1) return
    val target = ChatListOrder.jumpTarget(
        action, current.index, count, boundaryHidden = current.offset < -1,
    ) ?: return
    animateScrollToItem(target)
}

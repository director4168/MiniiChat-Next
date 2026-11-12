package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState

/** 参考rikkahub的MessageJumper单次跳转，适配反向列表及当前长消息的首尾边界。 */
internal suspend fun LazyListState.jumpMessage(action: Int, messageCount: Int) {
    val initial = layoutInfo
    val count = minOf(messageCount, initial.totalItemsCount)
    if (count <= 0 || action !in 0..3) return

    if (action == 3) {
        scrollToItem(0)
        return
    }
    if (action == 0) {
        // 屏外首条先同步测量，紧接着对齐；不用动画先露出消息末尾再跳回开头。
        scrollToItem(count - 1)
        scroll {
            val info = layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == count - 1 } ?: return@scroll
            val viewport = (info.viewportEndOffset - info.afterContentPadding).coerceAtLeast(0)
            scrollBy(ChatListOrder.jumpDistance(action, item.offset, item.size, viewport))
        }
        return
    }

    val viewport = (initial.viewportEndOffset - initial.afterContentPadding).coerceAtLeast(0)
    val visible = initial.visibleItemsInfo.filter {
        it.index in 0 until count && it.offset.toLong() + it.size > 0 && it.offset < viewport
    }
    val current = if (action == 1) visible.maxByOrNull { it.index } else visible.minByOrNull { it.index }
    current ?: return
    val target = ChatListOrder.jumpTarget(
        action, current.index, count,
        ChatListOrder.boundaryHidden(action, current.offset, current.size, viewport),
    ) ?: return
    val targetItem = visible.firstOrNull { it.index == target }
    if (targetItem != null) {
        // 已测量的目标直接按首尾位置计算一次位移，向下翻绝不再追加开头对齐。
        animateScrollBy(ChatListOrder.jumpDistance(action, targetItem.offset, targetItem.size, viewport))
    } else {
        // 相邻消息的尺寸在scrollBy重测后立即可读；两次位移间没有挂起或动画帧。
        scroll {
            scrollBy(ChatListOrder.revealDistance(action, current.offset, current.size, viewport, initial.mainAxisItemSpacing))
            val info = layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@scroll
            val size = (info.viewportEndOffset - info.afterContentPadding).coerceAtLeast(0)
            scrollBy(ChatListOrder.jumpDistance(action, item.offset, item.size, size))
        }
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.chat

import android.view.ViewTreeObserver
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** 正序列表增高后，在Android绘制前同步贴底，跳过尚未完成定位的中间帧。 */
@Composable
internal fun rememberChatBottomSettler(
    state: LazyListState,
    messageCount: Int,
    following: () -> Boolean,
): () -> Unit {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentCount by rememberUpdatedState(messageCount)
    val currentFollowing by rememberUpdatedState(following)
    val settle = remember(view, state, scope) {
        var settling = false
        val action: () -> Boolean = {
            fun shouldFollow(): Boolean = currentFollowing() && !state.isScrollInProgress
            fun needsScroll(): Boolean {
                val info = state.layoutInfo
                return currentCount > 0 && info.totalItemsCount == currentCount + 1 &&
                    info.visibleItemsInfo.isNotEmpty() && state.canScrollForward
            }
            if (!shouldFollow() || !needsScroll()) {
                true
            } else if (settling) {
                false
            } else {
                var ready = false
                settling = true
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        ready = settleChatBottomBeforeDraw(
                            following = ::shouldFollow,
                            needsScroll = ::needsScroll,
                            scrollToBottom = { state.scrollToItem(currentCount) },
                        )
                    } finally {
                        settling = false
                        if (!ready) view.postInvalidateOnAnimation()
                    }
                }
                ready
            }
        }
        action
    }
    DisposableEffect(view, state, settle) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener { settle() }
        observer.addOnPreDrawListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
    return remember(settle) { { settle(); Unit } }
}

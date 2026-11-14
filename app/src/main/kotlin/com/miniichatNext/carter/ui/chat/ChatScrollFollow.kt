/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.chat

/** 正序列表沿用rikkahub的底部跟随；阅读位置由LazyColumn保持，不做增高补偿。 */
internal data class ChatScrollFollow(
    val following: Boolean = true,
    val userScrolling: Boolean = false,
    val jumping: Boolean = false,
) {
    fun onUserScroll(): ChatScrollFollow =
        copy(following = false, userScrolling = true, jumping = false)

    fun onIdle(atBottom: Boolean): ChatScrollFollow =
        if (userScrolling) copy(following = atBottom, userScrolling = false) else this

    fun onJumpStarted(): ChatScrollFollow =
        copy(following = false, userScrolling = false, jumping = true)

    fun onJumpFinished(atBottom: Boolean): ChatScrollFollow =
        if (jumping && !userScrolling) copy(following = atBottom, jumping = false) else this

    fun followLatest(): ChatScrollFollow = ChatScrollFollow()

    fun shouldFollow(loading: Boolean, scrolling: Boolean): Boolean =
        loading && following && !userScrolling && !jumping && !scrolling
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.chat

/** 贴底重测完成后才提交这一帧，手动阅读时直接放行。 */
internal suspend fun settleChatBottomBeforeDraw(
    following: () -> Boolean,
    needsScroll: () -> Boolean,
    scrollToBottom: suspend () -> Unit,
): Boolean {
    if (!following() || !needsScroll()) return true
    scrollToBottom()
    return !following() || !needsScroll()
}

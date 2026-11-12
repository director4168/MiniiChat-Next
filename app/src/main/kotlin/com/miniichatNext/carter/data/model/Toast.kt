/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import com.miniichatNext.carter.util.newId

/**
 * 底部堆叠toast的单条数据。新条目追加到列表末尾=视觉底部，旧的被顶到上面
 */
data class ToastItem(
    val id: String = newId(),
    val message: String,
    val action: ToastAction = ToastAction.None,
    val withDismiss: Boolean = false,
    val tone: ToastTone = ToastTone.Normal,
)

enum class ToastTone { Normal, Warning, Error }

/** 通过Channel投递的toast请求（文本+语气，UI据此决定配色与停留时长） */
data class ToastPayload(
    val text: String,
    val tone: ToastTone = ToastTone.Normal,
)

/** toast操作按钮语义 */
sealed interface ToastAction {
    data object None : ToastAction
    data object Copy : ToastAction
    data class Manage(val kind: String) : ToastAction
}

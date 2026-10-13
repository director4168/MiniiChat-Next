package com.miniichatNext.carter.data.model

import com.miniichatNext.carter.util.newId

/**
 * 底部堆叠toast的单条数据。新条目追加到列表末尾=视觉底部，旧的被顶到上面。
 */
data class ToastItem(
    val id: String = newId(),
    val message: String,
    val action: ToastAction = ToastAction.None,
    val withDismiss: Boolean = false,
)

/** toast操作按钮语义 */
sealed interface ToastAction {
    data object None : ToastAction
    data object Copy : ToastAction
    data class Manage(val kind: String) : ToastAction
}

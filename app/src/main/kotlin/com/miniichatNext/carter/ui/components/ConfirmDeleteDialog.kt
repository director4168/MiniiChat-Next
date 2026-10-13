package com.miniichatNext.carter.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.miniichatNext.carter.R
import com.miniichatNext.carter.ui.markdown.MarkdownText

/**
 * 通用删除确认弹窗：标题="是否删除该[type]"，正文含目标名（走MarkdownText支持\n）。
 * 替代7+处分散的AlertDialog模板（Drawer/Assistants/McpScreens/Providers/Skills/Workspace）。
 *
 * @param targetName 被删除对象的名字（显示在正文）
 * @param typeLabel 类型名（如"对话/助手/服务商"）；null=标题仅"删除"
 * @param bodyText 自定义正文（null=走generic_delete_confirm%1$s）
 * @param confirmTextColor 确认按钮颜色（默认红色=危险操作）
 */
@Composable
fun ConfirmDeleteDialog(
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    bodyText: String? = null,
    confirmText: String = stringResource(R.string.delete),
    confirmTextColor: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.error,
    dismissText: String = stringResource(R.string.action_cancel),
    typeLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title, typeLabel ?: stringResource(R.string.item))) },
        text = {
            MarkdownText(
                text = (bodyText ?: stringResource(R.string.generic_delete_confirm, targetName))
                    .replace("\\n", "\n\n"),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = confirmTextColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

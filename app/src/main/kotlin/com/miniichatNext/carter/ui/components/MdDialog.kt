package com.miniichatNext.carter.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.miniichatNext.carter.ui.markdown.InlineMarkdownText
import com.miniichatNext.carter.util.openUrl
import com.miniichatNext.carter.ui.markdown.MarkdownText

/**
 * 全局Markdown弹窗：text槽统一走MarkdownText（支持粗体/斜体/行内代码/链接等，以及字面"\n"转真换行）。
 * 其它源码文件调用它弹带markdown内容的弹窗；extraContent可追加自定义控件（输入框/选项列表等）。
 * title/confirmText/dismissText可空（null=不显示对应部分）；onDismiss兼任AlertDialog的onDismissRequest。
 */
@Composable
fun MdDialog(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmText: String? = null,
    dismissText: String? = null,
    onConfirm: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        modifier = modifier,
        title = {
            // title同样走markdown（内联解析：粗体/斜体/代码/链接），避免*XXX*星号原样暴露
            if (title != null) {
                com.miniichatNext.carter.ui.markdown.InlineMarkdownText(
                    text = title,
                    palette = com.miniichatNext.carter.ui.markdown.BubblePalette,
                    onLink = { url -> ctx.openUrl(url) },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            // 原始字符串里字面的"\n"（两个字符）转成真换行，让块级结构（标题/列表/段落）正确解析
            MarkdownText(text = text.replace("\\n", "\n\n"))
            extraContent?.invoke()
        },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                TextButton(onClick = onConfirm) { Text(confirmText) }
            }
        },
        dismissButton = {
            if (dismissText != null && onDismiss != null) {
                TextButton(onClick = onDismiss) { Text(dismissText) }
            }
        },
    )
}

package com.miniichatNext.carter.util

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.miniichatNext.carter.R

/**
 * 复制到剪贴板+弹"已复制"toast（Compose上下文）。
 * 替代6+处分散的`clipboard.setText+Toast.makeText`模板。
 *
 * 用法：`val copy=rememberCopyToClipboard(); copy("text")`
 */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    return remember(clipboard, ctx) {
        { text ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(ctx, ctx.getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
    }
}

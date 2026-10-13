package com.miniichatNext.carter.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.miniichatNext.carter.R

/**
 * 通用"打开外部应用"扩展：拼装ACTION_VIEW intent+FileProvider标志+统一失败提示。
 * 替代9+处分散的Intent(Intent.ACTION_VIEW, Uri.parse(url))模板。
 */
fun Context.openUri(uri: Uri, mime: String? = null) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (mime != null) setDataAndType(uri, mime)
        }
        startActivity(intent)
    }.onFailure { e ->
        DebugLog_e(e.message ?: e.javaClass.simpleName)
        Toast.makeText(this, getString(R.string.open_external_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

fun Context.openUrl(url: String) = openUri(Uri.parse(url))

private fun Context.DebugLog_e(msg: String) {
    com.miniichatNext.carter.debug.DebugLog.w("IntentExt", "openUri failed: $msg")
}

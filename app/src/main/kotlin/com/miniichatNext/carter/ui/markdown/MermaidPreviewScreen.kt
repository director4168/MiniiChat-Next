package com.miniichatNext.carter.ui.markdown

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.miniichatNext.carter.R
import java.io.ByteArrayInputStream

private const val PREVIEW_BASE_URL = "https://miniichat.local/"

/**
 * Mermaid全屏预览：WebView原生双指缩放+滚动（上下左右移动），图表永远不会被截断
 * 缩放/滚动都发生在WebView viewport内部，Compose层不做任何变换。
 * 从聊天/更新弹窗的mermaid代码块右上角全屏按钮进入。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MermaidPreviewScreen(
    code: String,
    dark: Boolean,
    onBack: () -> Unit,
) {
    val bg = if (dark) androidx.compose.ui.graphics.Color(0xFF23262E)
    else androidx.compose.ui.graphics.Color(0xFFF2F3F5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding()
    ) {
        AndroidView(
            factory = { ctx ->
                val wv = WebView(ctx)
                wv.setBackgroundColor(0)
                with(wv.settings) {
                    javaScriptEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // WebView原生缩放：双指捏合缩放，滚动跟随手势
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url
                        if (!url.host.equals("miniichat.local", ignoreCase = true)) return null
                        if (url.path.orEmpty().endsWith("mermaid.min.js")) {
                            return runCatching {
                                WebResourceResponse(
                                    "text/javascript",
                                    "UTF-8",
                                    ctx.assets.open("html/mermaid.min.js"),
                                )
                            }.getOrNull()
                        }
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }
                wv.loadDataWithBaseURL(
                    PREVIEW_BASE_URL,
                    buildMermaidHtml(
                        code = code,
                        palette = if (dark) BubblePalette else LightContentPalette,
                        dark = dark,
                    ),
                    "text/html", "utf-8", null
                )
                wv
            },
            onRelease = { wv ->
                wv.stopLoading()
                wv.removeJavascriptInterface("CarterBridge")
                wv.removeAllViews()
                wv.destroy()
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        )

        TextButton(
            onClick = onBack,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) {
            Text(
                stringResource(R.string.mermaid_close),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

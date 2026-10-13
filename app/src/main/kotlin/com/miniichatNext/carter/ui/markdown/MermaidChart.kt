package com.miniichatNext.carter.ui.markdown

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.miniichatNext.carter.R
import java.io.ByteArrayInputStream

/** 虚拟域名：mermaid.min.js请求由拦截器本地响应，绝不出网 */
private const val MERMAID_HOST = "miniichat.local"
private const val MERMAID_BASE_URL = "https://miniichat.local/"
private const val MERMAID_ASSET_URL = "https://miniichat.local/assets/html/mermaid.min.js"

private fun Color.toCssHex(): String =
    String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * Mermaid图表：WebView加载本地mermaid.min.js渲染。
 * - 配色从[palette]深浅派生（深色气泡/浅色弹窗各自协调）
 * - JS侧渲染完成后回传实际高度
 * - 右上角工具栏：复制/查看源码/全屏预览
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MermaidChart(
    code: String,
    palette: InlinePalette,
    modifier: Modifier = Modifier,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 源码/预览切换（局部状态：图与代码互斥显示）
    var showSource by remember { mutableStateOf(false) }

    if (showSource) {
        CodeView(code, palette, modifier, onCopy = {
            clipboard.setText(AnnotatedString(code))
            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }, onPreview = { showSource = false })
        return
    }

    val dark = palette.codeBg.luminance() < 0.5f
    val html = remember(code, dark) { buildMermaidHtml(code, palette, dark) }
    var chartHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chartHeight = if (chartHeightPx > 0) with(density) { chartHeightPx.toDp() } else 200.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        AndroidView(
            factory = { ctx ->
                val wv = WebView(ctx)
                wv.setBackgroundColor(0)
                with(wv.settings) {
                    javaScriptEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url
                        if (!url.host.equals(MERMAID_HOST, ignoreCase = true)) return null
                        val path = url.path.orEmpty()
                        if (path.endsWith("mermaid.min.js")) {
                            return runCatching {
                                WebResourceResponse(
                                    "text/javascript",
                                    "UTF-8",
                                    ctx.assets.open("html/mermaid.min.js"),
                                )
                            }.getOrNull()
                        }
                        // favicon等其它请求：返回空响应，避免走网络产生DNS错误噪音
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }
                wv.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun setHeight(px: Int) {
                            wv.post { chartHeightPx = px.coerceIn(80, 6000) }
                        }
                    },
                    "CarterBridge",
                )
                wv.tag = html
                wv.loadDataWithBaseURL(MERMAID_BASE_URL, html, "text/html", "utf-8", null)
                wv
            },
            update = { wv ->
                if (wv.tag != html) {
                    wv.tag = html
                    wv.loadDataWithBaseURL(MERMAID_BASE_URL, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { wv ->
                wv.stopLoading()
                wv.removeJavascriptInterface("CarterBridge")
                wv.removeAllViews()
                wv.destroy()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 右上角工具栏
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = palette.codeFg,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { showSource = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Code,
                    contentDescription = stringResource(R.string.mermaid_view_source),
                    tint = palette.codeFg,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (onFullscreen != null) {
                IconButton(
                    onClick = { onFullscreen(code, dark) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = stringResource(R.string.mermaid_preview_action),
                        tint = palette.codeFg,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** mermaid源码视图：等宽字体显示+右上角「复制」「预览」按钮 */
@Composable
private fun CodeView(
    code: String,
    palette: InlinePalette,
    modifier: Modifier,
    onCopy: () -> Unit,
    onPreview: () -> Unit,
) {
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
    val lines = remember(code) { code.lineSequence().toList() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.codeBg)
            .border(1.dp, palette.outline, RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 语言标签栏（与普通代码框一致）
            Text(
                "mermaid",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = palette.langFg,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.outline),
            )
            // 行号+代码列（行号在横向滚动之外）
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Column(
                    modifier = Modifier
                        .drawBehind {
                            val x = size.width - 0.5.dp.toPx()
                            drawLine(
                                color = palette.outline,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .padding(start = 10.dp, end = 8.dp),
                ) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = "${index + 1}",
                            style = codeStyle,
                            color = palette.lineNumberFg,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 12.dp),
                ) {
                    lines.forEach { line ->
                        Text(text = line, style = codeStyle, color = palette.codeFg)
                    }
                }
            }
        }
        // 右上角：复制+预览
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)) {
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = palette.codeFg,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = stringResource(R.string.mermaid_show_preview),
                    tint = palette.codeFg,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}


internal fun buildMermaidHtml(code: String, palette: InlinePalette, dark: Boolean): String {
    val bg = palette.codeBg.toCssHex()
    val fg = palette.codeFg.toCssHex()
    val themeVars = (if (dark) DARK_THEME_VARS else LIGHT_THEME_VARS)
        .replace("__BG__", bg)
        .replace("__FG__", fg)
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=4.0">
<script src="$MERMAID_ASSET_URL"></script>
<style>
  html, body { margin: 0; padding: 0; background: $bg; }
  body { display: flex; justify-content: center; }
  .mermaid { padding: 12px 8px; width: 100%; box-sizing: border-box; }
  .mermaid svg { max-width: 100%; height: auto; }
  .md-fallback { font-family: monospace; font-size: 12px; padding: 10px; color: $fg; opacity: .65; white-space: pre-wrap; word-break: break-all; }
</style>
</head>
<body>
<pre class="mermaid">${escapeHtml(code)}</pre>
<script>
(function () {
  function fail(msg) {
    var d = document.createElement('div');
    d.className = 'md-fallback';
    d.textContent = msg;
    document.body.innerHTML = '';
    document.body.appendChild(d);
  }
  if (typeof mermaid === 'undefined') {
    fail('mermaid.min.js failed to load');
    return;
  }
  try {
    mermaid.initialize({
      startOnLoad: true,
      securityLevel: 'strict',
      theme: 'base',
      themeVariables: {
$themeVars
      }
    });
  } catch (e) {
    fail('mermaid init error: ' + (e && e.message ? e.message : e));
    return;
  }
  var tries = 0;
  var timer = setInterval(function () {
    try {
      var el = document.querySelector('.mermaid svg');
      if (el) {
        var h = Math.ceil(el.getBoundingClientRect().height) + 24;
        if (window.CarterBridge && CarterBridge.setHeight) CarterBridge.setHeight(h);
      }
    } catch (e) { }
    if (++tries >= 8) clearInterval(timer);
  }, 250);
})();
</script>
</body>
</html>
""".trimIndent()
}

private fun escapeHtml(s: String): String = buildString {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        else -> append(c)
    }
}


private val DARK_THEME_VARS = """
        primaryColor: '#3B4A66',
        primaryTextColor: '#E8EDF5',
        primaryBorderColor: '#5C6E94',
        secondaryColor: '#3E5A55',
        secondaryTextColor: '#E3EEEB',
        secondaryBorderColor: '#5E857D',
        tertiaryColor: '#4A4358',
        tertiaryTextColor: '#EFEAF6',
        tertiaryBorderColor: '#75698A',
        background: '__BG__',
        mainBkg: '#2E3543',
        secondBkg: '#343D4E',
        lineColor: '#8A94A6',
        textColor: '__FG__',
        nodeBorder: '#5C6E94',
        clusterBkg: '#262D39',
        clusterBorder: '#4A5464',
        edgeLabelBackground: '#2E3543',
        actorBkg: '#3B4A66',
        actorBorder: '#5C6E94',
        actorTextColor: '#E8EDF5',
        actorLineColor: '#8A94A6',
        noteBkgColor: '#3E4A5E',
        noteTextColor: '#E8EDF5',
        noteBorderColor: '#5C6E94'
""".trimIndent()

private val LIGHT_THEME_VARS = """
        primaryColor: '#E3E9F7',
        primaryTextColor: '#202A3C',
        primaryBorderColor: '#A9B7D4',
        secondaryColor: '#E1EFEA',
        secondaryTextColor: '#1F332E',
        secondaryBorderColor: '#A3C9BD',
        tertiaryColor: '#EBE6F5',
        tertiaryTextColor: '#2D2440',
        tertiaryBorderColor: '#BDB0D8',
        background: '__BG__',
        mainBkg: '#E8EDF5',
        secondBkg: '#ECF0F7',
        lineColor: '#5C6675',
        textColor: '__FG__',
        nodeBorder: '#A9B7D4',
        clusterBkg: '#EEF1F6',
        clusterBorder: '#C6CEDB',
        edgeLabelBackground: '#FFFFFF',
        actorBkg: '#E3E9F7',
        actorBorder: '#A9B7D4',
        actorTextColor: '#202A3C',
        actorLineColor: '#5C6675',
        noteBkgColor: '#FFF6D8',
        noteTextColor: '#4A3B14',
        noteBorderColor: '#E0CD8C'
""".trimIndent()

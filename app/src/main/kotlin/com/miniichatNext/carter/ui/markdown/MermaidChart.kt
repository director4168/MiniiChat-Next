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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.miniichatNext.carter.R
import java.io.ByteArrayInputStream
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** 虚拟域名：mermaid.min.js请求由拦截器本地响应，绝不出网 */
private const val MERMAID_HOST = "miniichat.local"

/** 图表卡片固定高度：高度恒定，滚动时不触发列表重排 */
private val MERMAID_CARD_HEIGHT = 200.dp
private const val MERMAID_BASE_URL = "https://miniichat.local/"
private const val MERMAID_ASSET_URL = "https://miniichat.local/assets/html/mermaid.min.js"

private fun Color.toCssHex(): String =
    String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * Mermaid图表，卡片化：上层类型+功能键，下层图表。
 * - 配色从[palette]深浅派生（深色气泡/浅色弹窗各自协调）
 * - JS侧用ResizeObserver持续回传实际高度（甘特/时序等异步布局的图不会被裁切）
 * - 内层WebView做静态预览（isEnabled=false：不吞手势，外层消息列表可正常滑动），
 * 点击卡片或全屏键进入MermaidPreviewScreen做双指缩放/平移
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

    var showSource by remember { mutableStateOf(false) }

    val dark = palette.codeBg.luminance() < 0.5f
    val density = LocalDensity.current
    // 高度固定（同rikkahub）：高度随内容变化会在滚动中触发列表重排，表现为"滑到这里往后退"
    val chartHeightPx = with(density) { MERMAID_CARD_HEIGHT.toPx() }
    val html = remember(code, dark, chartHeightPx) {
        buildMermaidHtml(code, palette, dark, maxHeightPx = (chartHeightPx - 24f).toInt().coerceAtLeast(120))
    }
    // 可见后才建WebView：一屏多张Mermaid同帧初始化会明显卡顿（进会话卡的主因之一）
    var everVisible by remember { mutableStateOf(false) }
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }

    ContentCard(
        label = "Mermaid",
        palette = palette,
        modifier = modifier,
        actions = {
            CardActionButton(
                icon = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.copy),
                tint = palette.langFg,
            ) {
                clipboard.setText(AnnotatedString(code))
                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
            }
            CardActionButton(
                icon = if (showSource) Icons.Filled.Visibility else Icons.Filled.Code,
                contentDescription = stringResource(
                    if (showSource) R.string.mermaid_show_preview else R.string.mermaid_view_source
                ),
                tint = palette.langFg,
            ) {
                showSource = !showSource
            }
            if (onFullscreen != null) {
                CardActionButton(
                    icon = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.mermaid_preview_action),
                    tint = palette.langFg,
                ) {
                    onFullscreen(code, dark)
                }
            }
        },
    ) {
        if (showSource) {
            SourceView(code, palette)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MERMAID_CARD_HEIGHT)
                    .onGloballyPositioned { coords ->
                        if (!everVisible) {
                            val b = coords.boundsInWindow()
                            if (b.bottom > 0f && b.top < screenHeightPx) everVisible = true
                        }
                    }
                    .clickable(enabled = onFullscreen != null) {
                        onFullscreen?.invoke(code, dark)
                    },
            ) {
                // 未可见时只留占位高度（不创建WebView）
                if (everVisible) AndroidView(
                    factory = { ctx ->
                        val wv = WebView(ctx)
                        wv.setBackgroundColor(0)
                        with(wv.settings) {
                            javaScriptEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = false
                            displayZoomControls = false
                        }
                        // 静态预览：禁用触摸，手势交给外层（否则WebView吞掉竖向滑动，消息列表滑不动）
                        wv.isEnabled = false
                        wv.isFocusable = false
                        wv.isClickable = false
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
                        wv.removeAllViews()
                        wv.destroy()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Mermaid源码视图：行号栏+等宽代码（横向滚动），与代码块保持一致的阅读体验 */
@Composable
private fun SourceView(code: String, palette: InlinePalette) {
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            text = lineNumbers,
            style = codeStyle,
            color = palette.lineNumberFg,
            textAlign = TextAlign.End,
            softWrap = false,
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
        )
        Text(
            text = code,
            style = codeStyle,
            color = palette.codeFg,
            softWrap = false,
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 12.dp),
        )
    }
}

internal fun buildMermaidHtml(
    code: String,
    palette: InlinePalette,
    dark: Boolean,
    maxHeightPx: Int = 176,
): String {
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
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<script src="$MERMAID_ASSET_URL"></script>
<style>
  html, body { margin: 0; padding: 0; background: $bg; }
  body { display: flex; justify-content: center; }
  .mermaid { padding: 12px 8px; width: fit-content; min-width: 100%; box-sizing: border-box; }
  .mermaid svg { background: transparent !important; max-width: 100%; height: auto; }
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
  var fitted = false;
  function fitSvg() {
    if (fitted) return true;
    var svg = document.querySelector('.mermaid svg');
    if (!svg) return false;
    try {
      var vb = svg.viewBox && svg.viewBox.baseVal;
      var natW = (vb && vb.width > 0) ? vb.width : svg.getBoundingClientRect().width;
      var natH = (vb && vb.height > 0) ? vb.height : svg.getBoundingClientRect().height;
      if (!natW || !natH) return false;
      var availW = Math.max(160, document.documentElement.clientWidth - 24);
      var maxH = $maxHeightPx;   // 卡片固定高度（由Compose侧传入），超高图按高度缩，整图可见
      var minScale = 0.40;   // 最小缩放限制：再小就看不清，超出部分交给全屏页缩放/平移
      var scale = Math.min(1, availW / natW, maxH / natH);
      if (scale < minScale) scale = minScale;
      svg.style.maxWidth = 'none';
      svg.style.width = Math.ceil(natW * scale) + 'px';
      svg.style.height = Math.ceil(natH * scale) + 'px';
      fitted = true;
      return true;
    } catch (e) { return false; }
  }
  function reportHeight() {
    try {
      fitSvg();
      var el = document.querySelector('.mermaid svg');
      var h = el ? Math.ceil(el.getBoundingClientRect().height) + 24
                 : Math.ceil(document.body.scrollHeight);
      if (h > 0 && window.CarterBridge && CarterBridge.setHeight) CarterBridge.setHeight(h);
    } catch (e) { }
  }
  reportHeight();
  // ResizeObserver持续跟进（甘特/时序等异步布局的图，2秒轮询内高度往往还没稳定）
  try {
    var ro = new ResizeObserver(function () { reportHeight(); });
    ro.observe(document.body);
    var target = document.querySelector('.mermaid');
    if (target) ro.observe(target);
  } catch (e) { }
  // 兜底：字体加载/动画结束后再补几次
  [300, 800, 1500, 3000, 6000].forEach(function (ms) { setTimeout(reportHeight, ms); });
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
        labelColor: '#E8EDF5',
        errorBkgColor: '#5A2B2B',
        errorTextColor: '#F5DADA',
        taskBorderColor: '#5C6E94',
        taskBkgColor: '#3B4A66',
        taskTextLightColor: '#E8EDF5',
        taskTextDarkColor: '#202A3C',
        taskTextOutsideColor: '#E8EDF5',
        activeTaskBkgColor: '#4A5B7D',
        activeTaskBorderColor: '#8A94A6',
        gridColor: '#3A4353',
        doneTaskBkgColor: '#33415C',
        doneTaskBorderColor: '#5C6E94',
        critBorderColor: '#B45454',
        critBkgColor: '#5A2B2B',
        todayLineColor: '#C58A5F',
        sectionBkgColor: '#262D39',
        altSectionBkgColor: '#2A3140',
        sectionBkgColor2: '#2A3140',
        pie1: '#3B4A66',
        pie2: '#3E5A55',
        pie3: '#4A4358',
        pie4: '#5C5040',
        pie5: '#454F63',
        pie6: '#525268',
        pie7: '#3F574F',
        pie8: '#5A4A56',
        pieTitleTextSize: '16px',
        pieSectionTextColor: '#E8EDF5',
        pieLegendTextColor: '#E8EDF5',
        pieStrokeColor: '#1F2530',
        pieOpacity: '0.9'
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
        labelColor: '#202A3C',
        errorBkgColor: '#F8DADA',
        errorTextColor: '#5A2B2B',
        taskBorderColor: '#A9B7D4',
        taskBkgColor: '#E3E9F7',
        taskTextLightColor: '#202A3C',
        taskTextDarkColor: '#202A3C',
        taskTextOutsideColor: '#202A3C',
        activeTaskBkgColor: '#CBD8F0',
        activeTaskBorderColor: '#8CA0C8',
        gridColor: '#D8DEE9',
        doneTaskBkgColor: '#D5DEEC',
        doneTaskBorderColor: '#A9B7D4',
        critBorderColor: '#D98A8A',
        critBkgColor: '#F8DADA',
        todayLineColor: '#C58A5F',
        sectionBkgColor: '#EEF1F6',
        altSectionBkgColor: '#F5F7FA',
        sectionBkgColor2: '#F5F7FA',
        pie1: '#E3E9F7',
        pie2: '#E1EFEA',
        pie3: '#EBE6F5',
        pie4: '#F6EEDD',
        pie5: '#E8ECF4',
        pie6: '#EDEAF3',
        pie7: '#E4EEEA',
        pie8: '#F1E9EC',
        pieTitleTextSize: '16px',
        pieSectionTextColor: '#202A3C',
        pieLegendTextColor: '#202A3C',
        pieStrokeColor: '#FFFFFF',
        pieOpacity: '0.9'
""".trimIndent()

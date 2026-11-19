/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.miniichatNext.carter.util.AppJson
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Composable
internal fun SvgCodePreview(code: String, palette: InlinePalette) {
    DiagramPreviewCard(code, svg = true, palette = palette)
}

/** 和rikkahub一样分离卡片预览与独立查看页，两种图表共用操作与WebView生命周期。 */
@Composable
internal fun DiagramPreviewCard(code: String, svg: Boolean, palette: InlinePalette, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val viewerDark = LocalDarkMode.current
    val dark = palette.codeBg.luminance() < 0.5f
    var source by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var everVisible by remember { mutableStateOf(false) }
    val heightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val html = remember(code, svg, dark, palette.codeBg, palette.codeFg) {
        runCatching {
            buildDiagramHtml(code, svg, dark,
                background = "#%06x".format(palette.codeBg.toArgb() and 0xffffff),
                foreground = "#%06x".format(palette.codeFg.toArgb() and 0xffffff))
        }
    }
    ContentCard(label = if (svg) "SVG" else "Mermaid", palette = palette, modifier = modifier, actions = {
        CardActionButton(Icons.Default.ContentCopy, stringResource(R.string.copy), palette.langFg) {
            clipboard.setText(AnnotatedString(code))
        }
        CardActionButton(if (source) Icons.Default.Visibility else Icons.Default.Code,
            stringResource(if (source) R.string.generated_image_preview else R.string.generated_image_code), palette.langFg) { source = !source }
        CardActionButton(Icons.Default.Fullscreen, stringResource(R.string.diagram_view), palette.langFg) { fullscreen = true }
    }) {
        if (source) DiagramSource(code, palette, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) else {
            Box(Modifier.fillMaxWidth().height(220.dp).onGloballyPositioned {
                val bounds = it.boundsInWindow()
                if (bounds.bottom > 0 && bounds.top < heightPx) everVisible = true
            }) {
                if (html.isFailure) {
                    Text(stringResource(R.string.diagram_render_failed, html.exceptionOrNull()?.message.orEmpty()),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                } else if (everVisible) {
                    DiagramWebView(html.getOrThrow(), script = !svg, zoom = false, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.matchParentSize().clickable { fullscreen = true })
            }
        }
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DiagramViewer(code, svg, viewerDark, onBack = { fullscreen = false })
    }
}

@Composable
internal fun DiagramSource(code: String, palette: InlinePalette, modifier: Modifier = Modifier, color: Color = palette.codeFg) {
    SelectionContainer {
        Text(code, color = color,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
            modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}

@Composable
internal fun DiagramViewer(code: String, svg: Boolean, dark: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reset by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var export by remember { mutableStateOf("") }
    val viewerDark = LocalDarkMode.current
    val colors = MaterialTheme.colorScheme
    val html = remember(code, svg, viewerDark, colors.surface, colors.onSurface) {
        runCatching { buildDiagramHtml(code, svg, viewerDark,
            background = "#%06x".format(colors.surface.toArgb() and 0xffffff),
            foreground = "#%06x".format(colors.onSurface.toArgb() and 0xffffff), fullscreen = true) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        val content = export
        if (uri != null && content.isNotBlank()) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: error("Destination is not writable")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Toast.makeText(context, context.getString(R.string.generated_image_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(if (svg) "SVG" else "Mermaid", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { source = !source }) {
                    Icon(if (source) Icons.Default.Visibility else Icons.Default.Code,
                        stringResource(if (source) R.string.generated_image_preview else R.string.generated_image_code))
                }
                IconButton(onClick = { reset++ }, enabled = !source && html.isSuccess) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.diagram_reset_zoom))
                }
                IconButton(enabled = html.isSuccess, onClick = {
                    if (svg) {
                        export = normalizedSvg(code)
                        save.launch("MiniiChat-SVG.svg")
                    } else {
                        webView?.evaluateJavascript("(function(){var s=document.querySelector('#diagram svg');return s?new XMLSerializer().serializeToString(s):''})()") { value ->
                            export = runCatching { (AppJson.json.parseToJsonElement(value) as? JsonPrimitive)?.contentOrNull.orEmpty() }.getOrDefault("")
                            if (export.isNotBlank()) save.launch("MiniiChat-Mermaid.svg")
                        }
                    }
                }) { Icon(Icons.Default.Download, stringResource(R.string.save)) }
            }
            if (html.isFailure) {
                Text(stringResource(R.string.diagram_render_failed, html.exceptionOrNull()?.message.orEmpty()), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp))
                DiagramSource(code, if (viewerDark) BubblePalette else LightContentPalette, Modifier.weight(1f).verticalScroll(rememberScrollState()).background(colors.surface), color = colors.onSurface)
            } else if (source) {
                DiagramSource(code, if (viewerDark) BubblePalette else LightContentPalette, Modifier.weight(1f).verticalScroll(rememberScrollState()).background(colors.surface), color = colors.onSurface)
            } else {
                Text(stringResource(R.string.diagram_zoom_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                key(reset) {
                    DiagramWebView(html.getOrThrow(), script = !svg, zoom = true,
                        modifier = Modifier.fillMaxWidth().weight(1f), onView = { webView = it })
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagramWebView(html: String, script: Boolean, zoom: Boolean, modifier: Modifier, onView: (WebView?) -> Unit = {}) {
    var loading by remember(html) { mutableStateOf(true) }
    var failure by remember(html) { mutableStateOf<String?>(null) }
    val currentOnView by rememberUpdatedState(onView)
    Box(modifier.clipToBounds()) {
        AndroidView(factory = { context ->
            WebView(context).apply {
                // WebView默认WRAP_CONTENT会和HTML百分比高度循环测量，显式跟随Compose视口。
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(0)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.builtInZoomControls = zoom
                settings.displayZoomControls = false
                settings.setSupportZoom(zoom)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                if (!zoom) setOnTouchListener { _, _ -> true }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            DebugLog.w("DiagramWebView", "js line=${message.lineNumber()}: ${message.message().take(500)}")
                        }
                        return true
                    }
                }
                currentOnView(this)
            }
        }, update = { view ->
            currentOnView(view)
            if (view.tag != html) {
                view.tag = html
                loading = true
                failure = null
                val document = html.toByteArray(Charsets.UTF_8)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
                        request.url.toString() != DIAGRAM_DOCUMENT_URL

                    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url
                        if (url.scheme == "https" && url.host == "miniichat.local") {
                            if (url.path == "/diagram/index.html") {
                                return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(document))
                            }
                            if (script && url.path == "/assets/html/mermaid.min.js") {
                                return WebResourceResponse("text/javascript", "UTF-8", webView.context.assets.open("html/mermaid.min.js"))
                            }
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && webView.tag == html) {
                            loading = false
                            failure = error.description.toString()
                            DebugLog.w("DiagramWebView", "load error=${error.errorCode}: ${error.description}")
                        }
                    }

                    override fun onPageFinished(webView: WebView, url: String?) {
                        if (url != DIAGRAM_DOCUMENT_URL || webView.tag != html) return
                        fun probe(attempt: Int) {
                            if (webView.tag != html) return
                            webView.evaluateJavascript(DIAGRAM_RENDER_PROBE) { result ->
                                if (webView.tag != html) return@evaluateJavascript
                                val metrics = runCatching {
                                    val text = (AppJson.json.parseToJsonElement(result) as JsonPrimitive).content
                                    AppJson.json.parseToJsonElement(text) as JsonObject
                                }.getOrNull()
                                val ready = (metrics?.get("width") as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { it > 0 } == true &&
                                    (metrics["height"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { it > 0 } == true &&
                                    (metrics["nodes"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true
                                if (ready) {
                                    loading = false
                                    failure = null
                                    DebugLog.d("DiagramWebView", "rendered native=${webView.width}x${webView.height} metrics=$metrics")
                                } else if (attempt < 30) {
                                    webView.postDelayed({ probe(attempt + 1) }, 400L)
                                } else {
                                    loading = false
                                    failure = (metrics?.get("error") as? JsonPrimitive)?.contentOrNull?.take(500)?.takeIf { it.isNotBlank() }
                                        ?: "No visible SVG after page load"
                                    DebugLog.w("DiagramWebView", "render incomplete native=${webView.width}x${webView.height} metrics=$metrics")
                                }
                            }
                        }
                        webView.post { probe(0) }
                    }
                }
                view.loadUrl(DIAGRAM_DOCUMENT_URL)
            }
        }, onRelease = { view ->
            view.tag = null
            currentOnView(null)
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
        }, modifier = Modifier.fillMaxSize())
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        failure?.let { message ->
            Text(stringResource(R.string.diagram_render_failed, message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp))
        }
    }
}

private const val DIAGRAM_DOCUMENT_URL = "https://miniichat.local/diagram/index.html"
private const val DIAGRAM_RENDER_PROBE = """
(function(){
 var root=document.querySelector('#diagram');
 var svg=root&&root.querySelector('svg');
 var box=svg&&svg.getBoundingClientRect();
 return JSON.stringify({width:box?box.width:0,height:box?box.height:0,
 nodes:svg?svg.querySelectorAll('*').length:0,error:svg?'':(root?root.textContent:'Missing document')});
})()
"""

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.miniichatNext.carter.data.mcp.McpToolResult
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.boundedGetClient
import com.miniichatNext.carter.util.readTextBounded
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import java.net.URI
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * 浏览器工具（browser_fetch）：AI抓取网页原始内容交付。
 * UA三态（mobile/desktop/custom）持久化到SharedPreferences，AI可通过args切换或自定义。
 * 免审批默认启用（只读网络请求，无破坏性）。
 */
object BrowserTools {

    private const val PREFS = "browser_tool"
    private const val KEY_UA_MODE = "ua_mode"      // mobile | desktop | custom
    private const val KEY_CUSTOM_UA = "custom_ua"

    private val renderMutex = Mutex()

    const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun uaMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UA_MODE, "mobile") ?: "mobile"

    fun customUa(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_UA, "") ?: ""

    fun setUa(context: Context, mode: String, customUa: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UA_MODE, mode)
            .putString(KEY_CUSTOM_UA, customUa.orEmpty())
            .apply()
    }

    fun currentUa(context: Context): String = when (uaMode(context)) {
        "desktop" -> UA_DESKTOP
        "custom" -> customUa(context).ifBlank { UA_MOBILE }
        else -> UA_MOBILE
    }

    /** args的ua参数：mobile/desktop/custom:xxx——只影响本次请求，不落盘 */
    private fun uaFor(context: Context, args: JsonObject): String {
        val uaArg = AppJson.str(args, "ua").orEmpty().trim()
        return when {
            uaArg.equals("mobile", true) -> UA_MOBILE
            uaArg.equals("desktop", true) -> UA_DESKTOP
            uaArg.startsWith("custom:", true) -> uaArg.substringAfter(':').trim().ifBlank { currentUa(context) }
            else -> currentUa(context)
        }
    }

    suspend fun execute(context: Context, args: JsonObject): McpToolResult {
        return try {
            val raw = AppJson.str(args, "url").orEmpty().trim()
            require(raw.isNotBlank()) { "Missing URL" }
            val address = URI(if ("://" in raw) raw else "https://$raw")
            require(address.scheme.lowercase() in setOf("http", "https") && address.host != null && address.rawUserInfo == null) { "Invalid HTTP URL" }
            val url = address.toASCIIString()
            val mode = AppJson.str(args, "mode").orEmpty().trim().lowercase()
            val ua = uaFor(context, args)
            val timeoutMs = (AppJson.int(args, "timeout_seconds") ?: 15).coerceIn(3, 60) * 1000L
            withTimeoutOrNull(timeoutMs) {
                if (mode == "render") return@withTimeoutOrNull renderFetch(context, url, ua)
                val (status, result) = readHttp(url, ua)
                if (mode != "http" && status in setOf(403, 429)) renderFetch(context, url, ua) else result
            } ?: McpToolResult(false, "", error = "Page read timed out")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            McpToolResult(false, "", error = failure.message ?: "Page read failed")
        }
    }

    private suspend fun readHttp(url: String, ua: String): Pair<Int, McpToolResult> = boundedGetClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, ua)
        header(HttpHeaders.Accept, "text/html,text/plain,application/xhtml+xml,application/json,application/xml,text/markdown")
    }.execute { response ->
        val code = response.status.value
        if (code !in 200..299) return@execute code to McpToolResult(false, "", error = "HTTP $code")
        val type = response.headers[HttpHeaders.ContentType].orEmpty()
        val mime = type.substringBefore(';').trim().lowercase()
        require(mime.isEmpty() || mime in TEXT_TYPES) { "Unsupported content type: $mime" }
        val encoding = Regex("charset\\s*=\\s*\"?([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val (html, truncated) = response.readTextBounded(256 * 1024, encoding, allowTruncation = true)
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val text = buildString {
            if (title.isNotBlank()) append("TITLE: ${title.take(1024)}\n\n")
            append(html.ifBlank { "(empty page)" })
        }
        val result = text.take(MAX_RESULT_CHARS) + if (truncated || text.length > MAX_RESULT_CHARS) "\n\n...[truncated]" else ""
        code to McpToolResult(true, result)
    }

    private val TEXT_TYPES = setOf("text/html", "text/plain", "application/xhtml+xml", "text/xml", "application/xml", "application/json", "text/markdown")
    private const val MAX_RESULT_CHARS = 32_000

    private suspend fun renderFetch(context: Context, url: String, ua: String): McpToolResult = renderMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val result = CompletableDeferred<McpToolResult>()
            val handler = Handler(Looper.getMainLooper())
            val view = WebView(context.applicationContext)
            var rendererGone = false
            var requested = false
            try {
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    userAgentString = ua
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme !in setOf("http", "https")

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (requested || result.isCompleted) return
                        requested = true
                        handler.postDelayed({
                            if (!result.isCompleted && !rendererGone) {
                                runCatching { view.evaluateJavascript(READABLE_JS) { json ->
                                    if (!result.isCompleted) result.complete(parseReadable(json.orEmpty()))
                                } }.onFailure { result.complete(McpToolResult(false, "", error = "Page evaluation failed")) }
                            }
                        }, 600L)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) result.complete(McpToolResult(false, "", error = "Page load failed"))
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererGone = true
                        result.complete(McpToolResult(false, "", error = "Web renderer stopped"))
                        return true
                    }
                }
                view.loadUrl(url)
                result.await()
            } finally {
                result.cancel()
                handler.removeCallbacksAndMessages(null)
                if (!rendererGone) runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
        }
    }

    /** 可读正文提取JS：article/[role=main]/main/...候选链（OpenMinis getReadable思路） */
    private val READABLE_JS = (
        "(function() {" +
        "var sels = ['article', '[role=main]', 'main', '.post-content', '.article-body', '.entry-content', '#content', '.content'];" +
        "var el = null, src = '';" +
        "for (var i = 0; i < sels.length; i++) {" +
        "var f = document.querySelector(sels[i]);" +
        "if (f && (f.innerText || '').length > 0) { el = f; src = sels[i]; break; }" +
        "}" +
        "if (!el) { el = document.body; src = 'body'; }" +
        "var text = (el.innerText || '').replace(/\\s+/g, ' ').trim().substring(0, 20000);" +
        "return JSON.stringify({title: document.title || '', text: text, source: src});" +
        "})()"
    )

    /** evaluateJavascript回传的是JSON字符串字面量（外层再包一层引号），先解一层再取字段 */
    private fun parseReadable(json: String): McpToolResult {
        val raw = runCatching {
            val el = AppJson.parse(json)
            (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: json
        }.getOrDefault(json)
        val obj = AppJson.parseObjectOrNull(raw) ?: return McpToolResult(false, "", error = "render: unreadable page")
        val title = AppJson.str(obj, "title").orEmpty()
        val text = AppJson.str(obj, "text").orEmpty()
        if (text.isBlank()) return McpToolResult(false, "", error = "render: empty page")
        val body = buildString {
            if (title.isNotBlank()) append("## $title\n\n")
            append(text)
        }
        val capped = if (body.length > MAX_RESULT_CHARS)
            body.take(MAX_RESULT_CHARS) + "\n\n...[truncated ${body.length - MAX_RESULT_CHARS} chars]"
        else body
        return McpToolResult(true, capped)
    }

}

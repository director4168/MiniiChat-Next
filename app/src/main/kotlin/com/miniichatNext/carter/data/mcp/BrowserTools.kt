package com.miniichatNext.carter.data.mcp

import android.content.Context
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 浏览器工具（browser_fetch）：AI抓取网页正文转Markdown交付。
 * UA三态（mobile/desktop/custom）持久化到SharedPreferences，AI可通过args切换或自定义。
 * 免审批默认启用（只读网络请求，无破坏性）。
 */
object BrowserTools {

    private const val PREFS = "browser_tool"
    private const val KEY_UA_MODE = "ua_mode"      // mobile | desktop | custom
    private const val KEY_CUSTOM_UA = "custom_ua"

    private val cookieHandlerInit = java.util.concurrent.atomic.AtomicBoolean(false)

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
            uaArg.startsWith("custom:", true) -> uaArg.removePrefix("custom:").trim().ifBlank { currentUa(context) }
            else -> currentUa(context)
        }
    }

    fun execute(context: Context, args: JsonObject): McpToolResult {
        val urlStr = AppJson.str(args, "url").orEmpty().trim()
        if (urlStr.isBlank()) return McpToolResult(false, "", error = "missing argument: url")
        val url = runCatching {
            // 无scheme时补https://
            URL(if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) urlStr else "https://$urlStr")
        }.getOrElse { return McpToolResult(false, "", error = "invalid url: $urlStr") }
        if (url.protocol !in setOf("http", "https")) {
            return McpToolResult(false, "", error = "only http/https supported")
        }
        // mode: auto（默认，HTTP被反爬拦截自动升级WebView渲染）/render（强制）/http（强制直连）
        val mode = AppJson.str(args, "mode").orEmpty().trim().lowercase()
        val ua = uaFor(context, args)
        val timeoutSec = (AppJson.int(args, "timeout_seconds") ?: 15).coerceIn(3, 60)
        if (mode == "render") return renderFetch(context, url.toString(), ua, timeoutSec)
        if (cookieHandlerInit.compareAndSet(false, true)) {
            runCatching { java.net.CookieHandler.setDefault(java.net.CookieManager()) }
        }
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                // 反爬缓解：带上浏览器级请求头组合（缺这些是Java客户端被识别的常见原因）
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                setRequestProperty("Cache-Control", "max-age=0")
                setRequestProperty("Sec-Fetch-Dest", "document")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Site", "none")
                setRequestProperty("Sec-Fetch-User", "?1")
                connectTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong()).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong()).toInt()
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                // 反爬拦截升级：403/429时用真实WebView渲染重试（借鉴OpenMinis；mode=http可禁用）
                if ((code == 403 || code == 429) && mode != "http") {
                    val rendered = renderFetch(context, url.toString(), ua, timeoutSec)
                    if (rendered.ok) return rendered
                    return McpToolResult(
                        false, "",
                        error = "HTTP $code (bot-blocked); render fallback: ${rendered.error ?: "blocked"}"
                    )
                }
                val hint = when (code) {
                    403 -> " (likely bot-blocked; try ua=desktop or custom:<ua>, or mode=render)"
                    429 -> " (rate limited; retry later)"
                    else -> ""
                }
                return McpToolResult(false, "", error = "HTTP $code$hint for $url")
            }
            // 内容类型限制：文本类才读（图片等直接报错，避免读二进制垃圾）
            val contentType = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
            if (contentType.isNotEmpty() && contentType !in TEXT_TYPES) {
                conn.disconnect()
                return McpToolResult(false, "", error = "unsupported content-type: $contentType (only text/html etc.)")
            }
            // 从Content-Type头解析charset（contentEncoding是gzip之类压缩编码，不能当charset用）
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(conn.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            val html = conn.inputStream.bufferedReader(charset(charset)).use { it.readText() }
            conn.disconnect()
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
            val md = htmlToMarkdown(html)
            val text = buildString {
                if (title.isNotBlank()) append("## $title\n\n")
                append(md.ifBlank { "(empty page)" })
            }
            val capped = if (text.length > MAX_RESULT_CHARS)
                text.take(MAX_RESULT_CHARS) + "\n\n...[truncated ${text.length - MAX_RESULT_CHARS} chars]"
            else text
            McpToolResult(true, capped)
        } catch (e: Exception) {
            DebugLog.w("BrowserTools", "fetch $url failed: ${e.message}")
            McpToolResult(false, "", error = e.message ?: "fetch failed")
        }
    }

    private val TEXT_TYPES = setOf(
        "text/html", "text/plain", "application/xhtml+xml", "text/xml",
        "application/xml", "application/json", "text/markdown",
    )
    private const val MAX_RESULT_CHARS = 32_000

    /** 极简HTML→Markdown：script/style剔除、块级换行、常见实体解码、标签内文本保留 */
    internal fun htmlToMarkdown(html: String): String {
        var s = html
        s = Regex("""(?is)<(script|style|noscript|svg|iframe|head)[^>]*>.*?</\1>""").replace(s, " ")
        s = Regex("""(?is)<!--.*?-->""").replace(s, " ")
        s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
        s = Regex("""(?i)</?(p|div|section|article|header|footer|nav|main|ul|ol|li|table|tr|td|th|h[1-6]|blockquote|pre|hr)[^>]*>""").replace(s, "\n")
        s = Regex("""(?is)<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""").replace(s) { m ->
            val text = m.groupValues.getOrNull(2)?.trim().orEmpty()
            val href = m.groupValues.getOrNull(1).orEmpty()
            if (text.isBlank()) "" else "[$text]($href)"
        }
        s = Regex("""(?s)<[^>]+>""").replace(s, " ")
        s = decodeEntities(s)
        s = s.replace(Regex("""[ \t]+"""), " ")
        s = s.replace(Regex("""\n\s*\n(\s*\n)+"""), "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String {
        var r = s
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ")
        r = Regex("""&#(\d+);""").replace(r) { m ->
            m.groupValues.getOrNull(1)?.toIntOrNull()?.toChar()?.toString() ?: ""
        }
        r = Regex("""&#x([0-9a-fA-F]+);""").replace(r) { m ->
            m.groupValues.getOrNull(1)?.toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        return r
    }

    /**
     * WebView渲染抓取（借鉴OpenMinis）：真实Chromium执行JS后提取可读正文。
     * 反爬站点（JS挑战/指纹校验）多数能通过；复用单例WebView，Cookie走全局CookieManager。
     * 仅文本提取——无截图/交互（完整浏览器自动化见OpenMinis BrowserTabPool体量）。
     */
    private fun renderFetch(context: Context, urlStr: String, ua: String, timeoutSec: Int): McpToolResult {
        val deferred = kotlinx.coroutines.CompletableDeferred<McpToolResult>()
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val wv = acquireWebView(context)
                    wv.settings.userAgentString = ua
                    var handled = false
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                            if (handled) return
                            handled = true
                            // JS渲染等待后提取可读正文（OpenMinis BrowserUseJS.getReadable候选选择器链思路）
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                view.evaluateJavascript(READABLE_JS) { json ->
                                    deferred.complete(parseReadable(json, urlStr))
                                }
                            }, 800L)
                        }
                    }
                    wv.loadUrl(urlStr)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!deferred.isCompleted) {
                            deferred.complete(McpToolResult(false, "", error = "render timeout"))
                        }
                    }, TimeUnit.SECONDS.toMillis(timeoutSec.toLong()).coerceAtLeast(10_000L))
                }.onFailure {
                    deferred.complete(McpToolResult(false, "", error = it.message ?: "webview error"))
                }
            }
        }.onFailure { deferred.complete(McpToolResult(false, "", error = "no main thread")) }
        return runCatching { kotlinx.coroutines.runBlocking { deferred.await() } }
            .getOrElse { McpToolResult(false, "", error = it.message ?: "render failed") }
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
    private fun parseReadable(json: String, urlStr: String): McpToolResult {
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

    /** 单例WebView（主线程创建；复用省去每请求起渲染进程的开销；Cookie走全局CookieManager） */
    @Volatile
    private var cachedWebView: android.webkit.WebView? = null

    private fun acquireWebView(context: Context): android.webkit.WebView {
        cachedWebView?.let { return it }
        val wv = android.webkit.WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        cachedWebView = wv
        return wv
    }
}

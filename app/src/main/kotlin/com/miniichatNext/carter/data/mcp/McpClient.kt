package com.miniichatNext.carter.data.mcp

import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP JSON-RPC 2.0客户端，纯JDK HttpURLConnection，不依赖Ktor/OkHttp。
 * initialize->notifications/initialized->tools/list，session id走Mcp-Session-Id头。
 */
class McpClient {

    private val json get() = AppJson.jsonCompact
    private val idGen = AtomicInteger(1)
    private val sessions = ConcurrentHashMap<String, String>()
    private val endpoints = ConcurrentHashMap<String, String>()
    private val sseChannels = ConcurrentHashMap<String, SseChannel>()
    private val sseUrls = ConcurrentHashMap<String, String>()
    private val sseLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun listTools(server: McpServerConfig): List<McpTool> = withContext(Dispatchers.IO) {
        val initResult = rpc(server, "initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "MiniiChat-Next")
                put("version", "1.0")
            })
        })
        DebugLog.i("Mcp", "initialize ok server=${server.name} result=${initResult.toString().take(200)}")

        runCatching { notify(server, "notifications/initialized", buildJsonObject { }) }

        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        while (pages++ < 100) {
            val page = rpc(server, "tools/list", buildJsonObject {
                cursor?.let { put("cursor", it) }
            })
            val toolsArr = page["tools"]?.jsonArray ?: JsonArray(emptyList())
            toolsArr.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val schema = obj["inputSchema"]?.let { json.encodeToString(JsonElement.serializer(), it) } ?: ""
                tools += McpTool(name = name, description = desc, inputSchema = schema)
            }
            val next = page["nextCursor"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != cursor }
                ?: break
            cursor = next
        }
        tools.distinctBy { it.name }
    }

    suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        argumentsJson: String
    ): McpToolResult = withContext(Dispatchers.IO) {
        val args = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        runCatching {
            val result = rpc(server, "tools/call", buildJsonObject {
                put("name", toolName)
                put("arguments", args)
            })
            val isError = result["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val text = extractContent(result)
            McpToolResult(ok = !isError, content = text)
        }.getOrElse { e ->
            DebugLog.e("Mcp", "callTool failed ${server.name}/$toolName: ${e.message}", e)
            McpToolResult(ok = false, content = "", error = e.message ?: "unknown error")
        }
    }

    private fun extractContent(result: JsonObject): String {
        val content = result["content"] as? JsonArray ?: return result.toString()
        val sb = StringBuilder()
        for (c in content) {
            val obj = c as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                "resource" -> (obj["resource"] as? JsonObject)
                    ?.get("text")?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                else -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
            }
        }
        return sb.toString().ifBlank { result.toString() }
    }

    private suspend fun rpc(
        server: McpServerConfig,
        method: String,
        params: JsonObject
    ): JsonObject {
        val id = idGen.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        return when (server.tp()) {
            McpTransport.STREAMABLE_HTTP -> streamableRpc(server, body, id)
            McpTransport.SSE -> sseRpc(server, body, id)
        }
    }

    private suspend fun notify(server: McpServerConfig, method: String, params: JsonObject) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString()
        val endpoint = if (server.tp() == McpTransport.SSE) endpoints[server.id] ?: server.url else server.url
        runCatching {
            val conn = openPost(server, endpoint, body)
            runCatching { conn.inputStream.use { it.readBytes() } }
            conn.disconnect()
        }
    }

    private fun streamableRpc(server: McpServerConfig, body: String, id: Int): JsonObject {
        val conn = openPost(server, server.url, body, acceptStream = true)
        conn.getHeaderField("Mcp-Session-Id")?.let { sessions[server.id] = it }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.readText().orEmpty() }
                .getOrDefault("")
            conn.disconnect()
            throw RuntimeException("HTTP $code: ${err.take(300)}")
        }
        val contentType = conn.contentType ?: ""
        val result = if (contentType.contains("text/event-stream", ignoreCase = true)) {
            readSseForResult(conn, id)
        } else {
            parseRpcResult(conn.inputStream.bufferedReader().use { it.readText() }, id)
        }
        conn.disconnect()
        return result
    }

    private data class SseEvent(val event: String, val data: String)

    private class SseChannel(private val connection: HttpURLConnection) {
        private val queue = LinkedBlockingQueue<SseEvent>()
        private val closed = AtomicBoolean(false)
        private val readerThread = Thread {
            runCatching {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var event = "message"
                    val data = StringBuilder()
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trim())
                            }
                            line.isEmpty() && data.isNotEmpty() -> {
                                queue.offer(SseEvent(event, data.toString()))
                                event = "message"
                                data.setLength(0)
                            }
                        }
                    }
                    if (data.isNotEmpty()) queue.offer(SseEvent(event, data.toString()))
                }
            }.onFailure { if (!closed.get()) queue.offer(SseEvent("error", it.message.orEmpty())) }
        }.apply { isDaemon = true }

        fun start() = readerThread.start()
        fun next(timeoutMs: Long): SseEvent? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { connection.disconnect() }
                readerThread.interrupt()
            }
        }
    }

    private fun openSse(server: McpServerConfig): Pair<SseChannel, String> {
        val existing = sseChannels[server.id]
        if (existing != null && sseUrls[server.id] == server.url) {
            return existing to endpoints.getValue(server.id)
        }
        existing?.close()
        sseChannels.remove(server.id)
        sessions.remove(server.id)
        endpoints.remove(server.id)
        sseUrls.remove(server.id)
        val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            connectTimeout = 20_000
            readTimeout = 0
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("SSE connect HTTP $code")
        }
        conn.getHeaderField("Mcp-Session-Id")?.let { sessions[server.id] = it }
        val channel = SseChannel(conn)
        channel.start()
        val endpointEvent = channel.next(15_000) ?: run {
            channel.close()
            throw RuntimeException("SSE endpoint not advertised")
        }
        if (endpointEvent.event == "error") {
            channel.close()
            throw RuntimeException("SSE endpoint error: ${endpointEvent.data}")
        }
        val endpoint = endpointEvent.data.trim()
        val resolved = if (endpoint.startsWith("http")) endpoint
        else "${baseRoot(server.url)}${if (endpoint.startsWith("/")) endpoint else "/$endpoint"}"
        endpoints[server.id] = resolved
        sseUrls[server.id] = server.url
        sseChannels[server.id] = channel
        return channel to resolved
    }

    private suspend fun sseRpc(server: McpServerConfig, body: String, id: Int): JsonObject =
        sseLocks.computeIfAbsent(server.id) { Mutex() }.withLock {
            val (channel, endpoint) = openSse(server)
            val conn = openPost(server, endpoint, body, acceptStream = false)
            val code = conn.responseCode
            if (code !in 200..299 && code != 202) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
                conn.disconnect()
                throw RuntimeException("HTTP $code: ${err.take(300)}")
            }
            val immediate = runCatching {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank()) null else parseRpcResult(text, id)
            }.getOrElse { error ->
                conn.disconnect()
                throw error
            }
            conn.disconnect()
            immediate ?: readSseForResult(channel, id)
        }

    private fun openPost(
        server: McpServerConfig,
        endpoint: String,
        body: String,
        acceptStream: Boolean = false
    ): HttpURLConnection {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty(
                "Accept",
                if (acceptStream) "application/json, text/event-stream" else "application/json"
            )
            sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    private fun readSseForResult(connection: HttpURLConnection, id: Int): JsonObject {
        val channel = SseChannel(connection)
        channel.start()
        return try { readSseForResult(channel, id) } finally { channel.close() }
    }

    private fun readSseForResult(channel: SseChannel, id: Int): JsonObject {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val event = channel.next((deadline - System.currentTimeMillis()).coerceAtMost(2_000)) ?: continue
            if (event.event == "error") throw RuntimeException("SSE error: ${event.data}")
            if (event.data.isBlank()) continue
            val response = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: continue
            if (response["id"]?.jsonPrimitive?.intOrNull != id) continue
            return parseRpcResult(event.data, id)
        }
        throw RuntimeException("SSE closed before result id=$id")
    }

    private fun parseRpcResult(text: String, id: Int): JsonObject {
        val obj = json.parseToJsonElement(text.trim()).jsonObject
        val responseId = obj["id"]?.jsonPrimitive?.intOrNull
            ?: throw RuntimeException("MCP response missing id (expected=$id)")
        if (responseId != id) throw RuntimeException("MCP response id mismatch: expected=$id actual=$responseId")
        obj["error"]?.takeUnless { it is JsonNull }?.let { err ->
            val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw RuntimeException("MCP error: $msg")
        }
        return obj["result"]?.jsonObject
            ?: throw RuntimeException("MCP response missing result (id=$id)")
    }

    private fun baseRoot(url: String): String {
        val u = URL(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.protocol}://${u.host}$port"
    }

    fun forget(serverId: String) {
        sessions.remove(serverId)
        endpoints.remove(serverId)
        sseUrls.remove(serverId)
        sseChannels.remove(serverId)?.close()
        sseLocks.remove(serverId)
    }
}

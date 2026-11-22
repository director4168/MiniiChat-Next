/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.mcp

import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.KeyedMutex
import com.miniichatNext.carter.util.readBounded
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class McpClient(private val isCurrent: (suspend (McpServerConfig) -> Boolean)? = null) {
    private class Session(val server: McpServerConfig) {
        private val connections = mutableSetOf<HttpURLConnection>()
        @Volatile private var closed = false
        var initialized = false
        var version: String? = null
        var id: String? = null
        var endpoint: String? = null
        @Volatile var stream: McpSseStream? = null

        @Synchronized
        fun attach(connection: HttpURLConnection) {
            check(!closed) { "MCP connection is closed" }
            connections.add(connection)
        }

        fun release(connection: HttpURLConnection) {
            synchronized(this) { connections.remove(connection) }
            connection.disconnect()
        }

        fun close() {
            val active = synchronized(this) {
                closed = true
                connections.toList().also { connections.clear() }
            }
            stream?.close()
            active.forEach { runCatching { it.disconnect() } }
        }
    }

    private val idGen = AtomicInteger(1)
    private val operations = KeyedMutex<String>()
    private val sessions = mutableMapOf<String, Session>()
    private var allowed: Map<String, McpServerConfig>? = null
    private var closed = false

    fun reconcile(servers: List<McpServerConfig>) {
        val active = servers.filter { it.enabled }.associateBy { it.id }
        val obsolete = synchronized(sessions) {
            if (closed) return
            allowed = active
            sessions.values.filter { session -> active[session.server.id]?.let { sameMcpSession(it, session.server) } != true }
                .also { entries -> entries.forEach { sessions.remove(it.server.id) } }
        }
        obsolete.forEach(Session::close)
    }

    fun close() {
        val active = synchronized(sessions) {
            closed = true
            allowed = null
            sessions.values.toList().also { sessions.clear() }
        }
        active.forEach(Session::close)
    }

    private fun session(server: McpServerConfig): Session {
        var previous: Session? = null
        val current = synchronized(sessions) {
            check(!closed && server.enabled) { "MCP connection is disabled" }
            allowed?.takeIf { isCurrent == null }?.let { configs ->
                check(configs[server.id]?.let { sameMcpSession(it, server) } == true) { "MCP connection has changed" }
            }
            val existing = sessions[server.id]
            if (existing != null && sameMcpSession(existing.server, server) &&
                (server.tp() != McpTransport.SSE || existing.stream?.isOpen == true)) existing
            else Session(server).also { previous = sessions.put(server.id, it) }
        }
        previous?.close()
        return current
    }

    private suspend fun <T> withSession(server: McpServerConfig, block: (Session) -> T): T = operations.withKey(server.id) {
        check(isCurrent?.invoke(server) != false) { "MCP connection has changed" }
        var opened: Session? = null
        try {
            runInterruptible(Dispatchers.IO) {
                val current = session(server).also { opened = it }
                ensureInitialized(current)
                block(current)
            }
        } catch (error: Exception) {
            opened?.let { current ->
                synchronized(sessions) { sessions.remove(server.id, current) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { current.close() }
            }
            throw error
        }
    }

    private fun ensureInitialized(session: Session) {
        if (session.initialized) return
        val result = rpc(session, "initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject { put("name", "MiniiChat-Next"); put("version", "1.0") })
        })
        session.version = result.string("protocolVersion")
        val endpoint = session.endpoint ?: session.server.url
        usingConnection(session, endpoint, request("notifications/initialized", buildJsonObject { })) { connection ->
            requireSuccess(connection)
        }
        session.initialized = true
    }

    suspend fun listTools(server: McpServerConfig): List<McpTool> = withSession(server) { session ->
        val tools = linkedMapOf<String, McpTool>()
        val seenCursors = hashSetOf<String>()
        var cursor: String? = null
        var schemaChars = 0L
        repeat(100) {
            val page = rpc(session, "tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (page["tools"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val name = obj.string("name")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return@forEach
                if (name in tools) return@forEach
                val desc = obj.string("description").orEmpty().take(4096)
                val schema = obj["inputSchema"]?.toString().orEmpty()
                schemaChars += schema.length + name.length + desc.length
                require(tools.size < 512 && schema.length <= 64 * 1024 && schemaChars <= 1024 * 1024) { "MCP tool list exceeds limit" }
                tools[name] = McpTool(name = name, description = desc, inputSchema = schema)
            }
            val next = page.string("nextCursor")?.takeIf { it.isNotBlank() } ?: return@withSession tools.values.toList()
            require(next.length <= 4096 && seenCursors.add(next)) { "Invalid MCP tool pagination cursor" }
            cursor = next
        }
        error("MCP tool pagination exceeds limit")
    }

    suspend fun callTool(server: McpServerConfig, toolName: String, argumentsJson: String): McpToolResult {
        return try {
            require(argumentsJson.length <= 256 * 1024) { "MCP tool arguments exceed limit" }
            val args = requireNotNull(AppJson.parseObjectOrNull(argumentsJson.ifBlank { "{}" })) { "Invalid MCP tool arguments" }
            withSession(server) { session ->
                val result = rpc(session, "tools/call", buildJsonObject { put("name", toolName); put("arguments", args) })
                val text = (result["content"] as? JsonArray).orEmpty().mapNotNull { part ->
                    val obj = part as? JsonObject ?: return@mapNotNull null
                    if (obj.string("type") == "resource") (obj["resource"] as? JsonObject)?.string("text") else obj.string("text")
                }.joinToString("\n").ifBlank { result.toString() }
                McpToolResult(ok = result.string("isError") != "true", content = if (text.length > MAX_RESULT_CHARS)
                    text.take(MAX_RESULT_CHARS) + "\n...[truncated]" else text)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            DebugLog.w("Mcp", "Tool request failed: ${error.javaClass.simpleName}")
            McpToolResult(false, "", error = error.message ?: "MCP request failed")
        }
    }

    private fun request(method: String, params: JsonObject, id: Int? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        id?.let { put("id", it) }
        put("method", method)
        put("params", params)
    }.toString()

    private fun rpc(session: Session, method: String, params: JsonObject): JsonObject {
        val id = idGen.getAndIncrement()
        val legacy = session.server.tp() == McpTransport.SSE
        val endpoint = if (legacy) openSse(session) else session.server.url
        return usingConnection(session, endpoint, request(method, params, id)) { connection ->
            requireSuccess(connection)
            if (!legacy) connection.getHeaderField("Mcp-Session-Id")?.let { session.id = it }
            when {
                legacy -> {
                    val immediate = if (connection.responseCode == 202 || connection.responseCode == 204) "" else connection.readBody()
                    if (immediate.isBlank()) readResult(requireNotNull(session.stream), id) else parseRpcResult(immediate, id)
                }
                connection.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true ->
                    McpSseStream(connection).use { readResult(it, id) }
                else -> parseRpcResult(connection.readBody(), id)
            }
        }
    }

    private fun openSse(session: Session): String {
        session.endpoint?.let { return it }
        val connection = openConnection(session, session.server.url, post = false)
        try {
            requireSuccess(connection)
            require(connection.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true) { "MCP SSE needs an event stream" }
            val stream = McpSseStream(connection)
            session.stream = stream
            val event = stream.next(deadline(15))
            require(event.name == "endpoint") { "MCP SSE did not advertise an endpoint" }
            session.id = connection.getHeaderField("Mcp-Session-Id")
            return resolveMcpEndpoint(connection.url.toString(), event.data).also { session.endpoint = it }
        } catch (error: Exception) {
            session.stream?.close()
            session.release(connection)
            throw error
        }
    }

    private fun openConnection(session: Session, endpoint: String, post: Boolean): HttpURLConnection {
        val url = checkedMcpUri(endpoint).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = if (post) "POST" else "GET"
            doOutput = post
            connectTimeout = 20_000
            readTimeout = 90_000
            for ((name, value) in session.server.headers) if (name.isNotBlank() && name.lowercase(java.util.Locale.ROOT) !in PROTOCOL_HEADERS) {
                setRequestProperty(name, value)
            }
            setRequestProperty("Accept", if (post) "application/json, text/event-stream" else "text/event-stream")
            if (post) setRequestProperty("Content-Type", "application/json")
            session.id?.let { setRequestProperty("Mcp-Session-Id", it) }
            session.version?.let { setRequestProperty("MCP-Protocol-Version", it) }
        }
        session.attach(connection)
        return connection
    }

    private inline fun <T> usingConnection(session: Session, endpoint: String, body: String, block: (HttpURLConnection) -> T): T {
        val connection = openConnection(session, endpoint, post = true)
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            block(connection)
        } finally { session.release(connection) }
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val error = connection.errorStream?.use { input ->
                runCatching { input.readBounded(8192).toString(Charsets.UTF_8).take(300) }.getOrDefault("")
            }.orEmpty()
            throw IOException("MCP HTTP $code${if (error.isBlank()) "" else ": $error"}")
        }
    }

    private fun HttpURLConnection.readBody(): String = inputStream.use { it.readBounded(MAX_BODY_BYTES).toString(Charsets.UTF_8) }

    private fun readResult(stream: McpSseStream, id: Int): JsonObject {
        val deadline = deadline(90)
        while (true) {
            if (System.nanoTime() >= deadline) throw IOException("MCP SSE response timed out")
            val event = stream.next(deadline)
            if (event.name == "error") throw IOException("MCP SSE error: ${event.data.take(300)}")
            val obj = AppJson.parseObjectOrNull(event.data) ?: continue
            if ((obj["id"] as? JsonPrimitive)?.intOrNull != id) continue
            return rpcResult(obj, id)
        }
    }

    private fun parseRpcResult(text: String, id: Int): JsonObject =
        rpcResult(requireNotNull(AppJson.parseObjectOrNull(text)) { "Invalid MCP response" }, id)

    private fun rpcResult(obj: JsonObject, id: Int): JsonObject {
        require((obj["id"] as? JsonPrimitive)?.intOrNull == id) { "MCP response ID mismatch" }
        obj["error"]?.takeUnless { it is JsonNull }?.let { error ->
            throw IOException("MCP error: ${((error as? JsonObject)?.string("message") ?: error.toString()).take(300)}")
        }
        return obj["result"] as? JsonObject ?: error("MCP response missing result")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun deadline(seconds: Long): Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)

    private companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_RESULT_CHARS = 32 * 1024
        val PROTOCOL_HEADERS = setOf("accept", "content-type", "host", "mcp-session-id", "mcp-protocol-version")
    }
}

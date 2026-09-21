package com.miniichatNext.carter.data.mcp

import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP JSON-RPC 2.0客户端，纯JDK HttpURLConnection，不依赖Ktor/OkHttp。
 * initialize -> notifications/initialized -> tools/list，session id走Mcp-Session-Id头。
 */
class McpClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val idGen = AtomicInteger(1)
    private val sessions = mutableMapOf<String, String>()

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

        val listResult = rpc(server, "tools/list", buildJsonObject { })
        val toolsArr = listResult["tools"]?.jsonArray ?: JsonArray(emptyList())
        toolsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val schema = obj["inputSchema"]?.let { json.encodeToString(JsonElement.serializer(), it) } ?: ""
            McpTool(name = name, description = desc, inputSchema = schema)
        }
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
        val endpoint = if (server.tp() == McpTransport.SSE) sseEndpoint(server) else server.url
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

    private fun sseEndpoint(server: McpServerConfig): String {
        val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            connectTimeout = 20_000
            readTimeout = 20_000
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("SSE connect HTTP $code")
        }
        var endpoint = ""
        runCatching {
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var ev = ""
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("event:")) {
                        ev = line.removePrefix("event:").trim()
                    } else if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (ev == "endpoint" || data.contains("/message") || data.startsWith("/")) {
                            endpoint = data
                            break
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (endpoint.isBlank()) throw RuntimeException("SSE endpoint not advertised")
        return if (endpoint.startsWith("http")) endpoint
        else "${baseRoot(server.url)}${if (endpoint.startsWith("/")) endpoint else "/$endpoint"}"
    }

    private fun sseRpc(server: McpServerConfig, body: String, id: Int): JsonObject {
        val endpoint = sseEndpoint(server)
        val conn = openPost(server, endpoint, body, acceptStream = true)
        val code = conn.responseCode
        if (code !in 200..299 && code != 202) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.readText().orEmpty() }
                .getOrDefault("")
            conn.disconnect()
            throw RuntimeException("HTTP $code: ${err.take(300)}")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        runCatching { return parseRpcResult(text, id) }.getOrNull()
        return readSseSingle(server, id)
    }

    private fun readSseSingle(server: McpServerConfig, id: Int): JsonObject {
        val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 20_000
            readTimeout = 30_000
            for ((k, v) in server.headers) if (k.isNotBlank()) setRequestProperty(k, v)
        }
        val result = readSseForResult(conn, id)
        conn.disconnect()
        return result
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

    private fun readSseForResult(conn: HttpURLConnection, id: Int): JsonObject {
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (sb.isNotEmpty()) {
                        val parsed = runCatching { parseRpcResult(sb.toString(), id) }.getOrNull()
                        if (parsed != null) return parsed
                        sb.setLength(0)
                    }
                    continue
                }
                if (line.startsWith("data:")) sb.append(line.removePrefix("data:").trim())
            }
            if (sb.isNotEmpty()) return parseRpcResult(sb.toString(), id)
        }
        throw RuntimeException("SSE closed before result id=$id")
    }

    private fun parseRpcResult(text: String, id: Int): JsonObject {
        val obj = json.parseToJsonElement(text.trim()).jsonObject
        obj["error"]?.let { err ->
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
    }
}

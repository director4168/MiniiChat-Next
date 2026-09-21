package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderType
import kotlinx.serialization.json.JsonObjectBuilder
import com.miniichatNext.carter.data.model.ProviderOverride
import com.miniichatNext.carter.data.model.PromptCacheTtl
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.debug.DebugLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class LlmClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 180_000
            }
        }
    }

    /** 工具JSON Schema原文 -> JsonElement；解析失败时退回空object schema */
    private fun toolSchema(raw: String): kotlinx.serialization.json.JsonElement =
        runCatching { json.parseToJsonElement(raw) }
            .getOrElse {
                kotlinx.serialization.json.buildJsonObject {
                    put("type", "object")
                    put("properties", kotlinx.serialization.json.buildJsonObject { })
                }
            }

    internal fun buildRequestBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList(),
        override: ProviderOverride? = null,
        thinking: ThinkingLevel = ThinkingLevel.AUTO,
    ): String {
        return when {
            provider.type() == ProviderType.CLAUDE ->
                buildClaudeBody(provider, modelId, messages, stream, temperature, tools, override, thinking)
            provider.effectiveResponseApi(override?.responseApi) ->
                buildResponseBody(provider, modelId, messages, stream, temperature, tools, thinking)
            else ->
                buildOpenAiBody(provider, modelId, messages, stream, temperature, tools, thinking)
        }
    }
    fun chatStream(
        provider: ProviderConfig,
        settings: AppSettings,
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        override: ProviderOverride? = null,
        thinking: ThinkingLevel = ThinkingLevel.AUTO,
    ): Flow<StreamDelta> = flow {
        val bodyText = buildRequestBody(
            provider = provider,
            modelId = modelId,
            messages = messages,
            stream = settings.stream,
            temperature = settings.temperature,
            tools = tools,
            override = override,
            thinking = thinking
        )
        val isClaude = provider.type() == ProviderType.CLAUDE
        val endpoint = when {
            isClaude -> claudeMessagesEndpoint(provider.baseUrl)
            provider.effectiveResponseApi(override?.responseApi) -> responsesEndpoint(provider.baseUrl)
            else -> chatEndpoint(provider.baseUrl, provider.chatCompletionsPath)
        }
        val fallbackEndpoint = if (isClaude && claudeNeedsV1Fallback(provider.baseUrl)) {
            claudeV1MessagesEndpoint(provider.baseUrl)
        } else null
        DebugLog.d(
            "Llm",
            "chatStream provider=${provider.name} type=${provider.type()} model=$modelId " +
                "messages=${messages.size} stream=${settings.stream} endpoint=$endpoint " +
                "bodyChars=${bodyText.length}"
        )
        suspend fun doPost(target: String) {
            DebugLog.i("Llm", "POST $target")
            client.preparePost(target) {
                contentType(ContentType.Application.Json)
                headers {
                    if (isClaude) {
                        for ((k, v) in claudeAuthHeaders(provider)) append(k, v)
                    } else {
                        if (provider.apiKey.isNotBlank()) {
                            append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                        }
                        for ((k, v) in provider.customHeaders) {
                            if (k.isBlank()) continue
                            append(k, v)
                        }
                    }
                    append(
                        HttpHeaders.Accept,
                        if (settings.stream) "text/event-stream" else "application/json"
                    )
                }
                setBody(bodyText)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                    DebugLog.e(
                        "Llm",
                        "HTTP ${response.status.value} from $target, body=${errBody.take(500)}"
                    )
                    val msg = "HTTP ${response.status.value}: ${errBody.take(500)}"
                    if (isClaude && response.status == HttpStatusCode.NotFound) {
                        throw ClaudeEndpoint404(msg)
                    }
                    throw RuntimeException(msg)
                }
                DebugLog.d("Llm", "HTTP ${response.status.value} OK from $target (stream=${settings.stream})")
                if (settings.stream) {
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    when (provider.type()) {
                        ProviderType.OPENAI -> readOpenAiStream(channel)
                        ProviderType.CLAUDE -> readClaudeStream(channel)
                    }
                } else {
                    when (provider.type()) {
                        ProviderType.OPENAI -> readOpenAiNonStream(response)
                        ProviderType.CLAUDE -> readClaudeNonStream(response)
                    }
                }
            }
        }
        if (fallbackEndpoint != null) {
            try {
                doPost(endpoint)
            } catch (e: ClaudeEndpoint404) {
                doPost(fallbackEndpoint)
            }
        } else {
            doPost(endpoint)
        }
    }

    suspend fun listModels(provider: ProviderConfig): List<String> = when (provider.type()) {
        ProviderType.CLAUDE -> listClaudeModels(provider)
        else -> listOpenAiModels(provider)
    }

    private fun buildOpenAiBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList(),
        thinking: ThinkingLevel = ThinkingLevel.AUTO,
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        // 思考等级：对话级唯一来源（item 1：服务商/模型不再有这条设置）
        val effThinking = thinking
        val reasoningOk = provider.supportsReasoning(modelId)
        val host = hostOf(provider.baseUrl)
        val msgsJson = kotlinx.serialization.json.buildJsonArray {
            for (m in messages) {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", m.role)
                    if (m.isMultipart()) {
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            for (part in m.parts!!) {
                                add(json.encodeToJsonElement(ChatPart.serializer(), part))
                            }
                        })
                    } else {
                        put("content", m.content)
                    }
                    if (!m.toolCalls.isNullOrEmpty()) {
                        put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                            for (tc in m.toolCalls) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    }
                                })
                            }
                        })
                    }
                    if (m.toolCallId != null) {
                        put("tool_call_id", m.toolCallId)
                    }
                })
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            // 开了推理就别带temperature：o系列 / DeepSeek等会直接报错
            if (effThinking == ThinkingLevel.OFF) put("temperature", effTemp)
            put("messages", msgsJson)
            modelCfg?.maxTokens?.let { put("max_tokens", it) }
            // 各家的推理参数形状不同，按host特判（见applyReasoning）
            if (reasoningOk) applyReasoning(host, effThinking)
            for ((k, v) in provider.extraBody) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.buildJsonArray {
                    for (t in tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", toolSchema(t.inputSchema))
                            }
                        })
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
    private fun buildResponseBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList(),
        thinking: ThinkingLevel = ThinkingLevel.AUTO,
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        val input = kotlinx.serialization.json.buildJsonArray {
            for (m in messages) {
                if (m.role == "system") {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", m.content)
                    })
                } else {
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }
        // 思考等级：对话级唯一来源（item 1：服务商/模型不再有这条设置）
        val effThinking = thinking
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            if (effThinking == ThinkingLevel.OFF) put("temperature", effTemp)
            put("input", input)
            modelCfg?.maxTokens?.let { put("max_output_tokens", it) }
            // Response API的推理强度走reasoning.effort
            if (provider.supportsReasoning(modelId)) effThinking.effort?.let { effort ->
                putJsonObject("reasoning") { put("effort", effort) }
            }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
    private suspend fun listOpenAiModels(provider: ProviderConfig): List<String> {
        val resp = client.get(modelsEndpoint(provider.baseUrl)) {
            headers {
                if (provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
        }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw RuntimeException("HTTP ${resp.status.value}: ${err.take(300)}")
        }
        val text = resp.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ModelsResponse.serializer(), text)
        }.getOrNull()
        if (parsed != null && parsed.data.isNotEmpty()) {
            return parsed.data.map { it.id }.distinct().sorted()
        }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.distinct().sorted()
    }
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readOpenAiStream(
        channel: ByteReadChannel
    ) {
        val pendingTools = mutableMapOf<Int, PendingToolCall>()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isEmpty()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            // 有些网关200里塞{"error":{...}}，解析后看有没有顶层error字段
            // 不要用字符串匹配，否则模型正文里出现error字样会被误判
            val element = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: continue
            (element as? JsonObject)?.get("error")?.let { errEl ->
                val errMsg = (errEl as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                throw RuntimeException((errMsg ?: errEl.toString()).take(500))
            }
            val chunk = runCatching {
                json.decodeFromJsonElement(ChatChunk.serializer(), element)
            }.getOrNull() ?: continue
            val delta = chunk.choices.firstOrNull()?.delta
            val text = delta?.content.orEmpty()
            val reasoning = delta?.reasoningContent.orEmpty()
            if (text.isNotEmpty() || reasoning.isNotEmpty()) {
                emit(StreamDelta(text = text, reasoning = reasoning))
            }
            delta?.toolCalls?.forEach { tc ->
                val p = pendingTools.getOrPut(tc.index) { PendingToolCall() }
                if (tc.id != null) p.id = tc.id
                if (tc.function?.name != null) p.name = tc.function.name
                if (tc.function != null) p.args.append(tc.function.arguments)
            }
            chunk.usage?.let {
                emit(StreamDelta(usage = TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens
                )))
            }
        }
        if (pendingTools.isNotEmpty()) {
            emit(StreamDelta(toolCalls = pendingTools.values
                .filter { it.name.isNotBlank() }
                .map { ToolCallDelta(id = it.id, name = it.name, argumentsJson = it.args.toString()) }))
        }
    }

    private class PendingToolCall(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readOpenAiNonStream(
        response: io.ktor.client.statement.HttpResponse
    ) {
        val text = response.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ChatResponse.serializer(), text)
        }.getOrNull()
        val msg = parsed?.choices?.firstOrNull()?.message
        val content = msg?.content
        if (!content.isNullOrEmpty()) emit(StreamDelta(text = content))
        msg?.toolCalls?.takeIf { it.isNotEmpty() }?.let { tcs ->
            emit(StreamDelta(toolCalls = tcs.map { ToolCallDelta(
                id = it.id ?: "",
                name = it.function?.name ?: "",
                argumentsJson = it.function?.arguments ?: ""
            ) }))
        }
        parsed?.usage?.let {
            emit(StreamDelta(usage = TokenUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )))
        }
    }

internal fun claudeAuthHeaders(provider: ProviderConfig): List<Pair<String, String>> =
    buildList {
        if (provider.apiKey.isNotBlank()) add("x-api-key" to provider.apiKey)
        add("anthropic-version" to "2023-06-01")
        for ((k, v) in provider.customHeaders) {
            if (k.isBlank()) continue
            add(k to v)
        }
    }
    private fun buildClaudeBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolSpec> = emptyList(),
        override: ProviderOverride? = null,
        thinking: ThinkingLevel = ThinkingLevel.AUTO,
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        // 思考等级：对话级唯一来源（item 1：服务商/模型不再有这条设置）
        val effThinking = thinking
        val reasoningOk = provider.supportsReasoning(modelId)
        val effCache = provider.effectivePromptCache(override?.promptCache)
        val cacheTtl = provider.effectiveCacheTtl(override?.promptCacheTtl)
        val effMaxTokens = if (effThinking.budgetTokens > 0) {
            maxOf(modelCfg?.maxTokens ?: provider.maxTokens, effThinking.budgetTokens + 1024)
        } else {
            modelCfg?.maxTokens ?: provider.maxTokens
        }
        var systemText: String? = null
        val convMsgs = mutableListOf<ChatMessage>()
        for (m in messages) {
            if (m.role == "system") {
                systemText = if (systemText.isNullOrBlank()) m.content
                else (systemText + "\n\n" + m.content)
            } else {
                convMsgs.add(m)
            }
        }
        val msgsJson = kotlinx.serialization.json.buildJsonArray {
            for (m in convMsgs) {
                add(kotlinx.serialization.json.buildJsonObject {
                    if (m.role == "tool") {
                        put("role", "user")
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                m.toolCallId?.let { put("tool_use_id", it) }
                                put("content", m.content)
                            })
                        })
                    } else if (!m.toolCalls.isNullOrEmpty()) {
                        put("role", "assistant")
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            if (m.content.isNotBlank()) {
                                add(buildJsonObject { put("type", "text"); put("text", m.content) })
                            }
                            for (tc in m.toolCalls) {
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", toolSchema(tc.arguments.ifBlank { "{}" }))
                                })
                            }
                        })
                    } else if (m.isMultipart()) {
                        put("role", m.role)
                        val parts = mutableListOf<JsonObject>()
                        if (m.content.isNotBlank()) {
                            parts.add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                        }
                        for (att in m.parts!!) {
                            when (att) {
                                is ChatPart.Text -> parts.add(buildJsonObject {
                                    put("type", "text"); put("text", att.text)
                                })
                                is ChatPart.ImageUrl -> {
                                    val url = att.imageUrl.url
                                    val match = Regex("^data:([^;]+);base64,(.+)$").matchEntire(url)
                                    if (match != null) {
                                        parts.add(buildJsonObject {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", match.groupValues[1])
                                                put("data", match.groupValues[2])
                                            }
                                        })
                                    }
                                }
                                is ChatPart.ToolUse -> parts.add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", att.id)
                                    put("name", att.name)
                                    put("input", att.input)
                                })
                                is ChatPart.ToolResultPart -> parts.add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", att.toolUseId)
                                    put("content", att.content)
                                    if (att.isError) put("is_error", true)
                                })
                            }
                        }
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            parts.forEach { add(it) }
                        })
                    } else {
                        put("role", m.role)
                        put("content", m.content)
                    }
                })
            }
        }
        val finalMsgs = if (effCache) withMessagesCacheControl(msgsJson, cacheTtl) else msgsJson
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("max_tokens", effMaxTokens)
            if (effThinking == ThinkingLevel.OFF) put("temperature", effTemp)
            put("messages", finalMsgs)
            // 顶层cache_control：让Anthropic自动管理缓存断点
            if (effCache) put("cache_control", cacheControlOf(cacheTtl))
            if (!systemText.isNullOrBlank()) {
                if (effCache) {
                    put("system", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", systemText)
                            put("cache_control", cacheControlOf(cacheTtl))
                        })
                    })
                } else {
                    put("system", systemText)
                }
            }
            if (reasoningOk && effThinking != ThinkingLevel.OFF) {
                putJsonObject("thinking") {
                    if (effThinking == ThinkingLevel.AUTO) {
                        // AUTO -> 新API的adaptive，让模型自己决定强度
                        put("type", "adaptive")
                        put("display", "summarized")
                    } else {
                        // 显式档位 -> 旧API形状，兼容Claude 3.7/4
                        put("type", "enabled")
                        put("budget_tokens", effThinking.budgetTokens)
                    }
                }
            }
            for ((k, v) in provider.extraBody) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.buildJsonArray {
                    for ((i, t) in tools.withIndex()) {
                        val base = buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", toolSchema(t.inputSchema))
                        }
                        // 缓存断点打在最后一个工具上
                        add(if (effCache && i == tools.lastIndex) {
                            JsonObject(base + ("cache_control" to cacheControlOf(cacheTtl)))
                        } else base)
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** 从baseUrl取出host（容忍没写scheme的写法） */
    private fun hostOf(baseUrl: String): String {
        val t = baseUrl.trim()
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return runCatching { java.net.URI(withScheme).host ?: "" }
            .getOrDefault("")
            .lowercase()
    }

    /**
     * 写推理参数。
     *
     * 各家形状并不统一 —— 一律发reasoning_effort是行不通的：服务端会**静默忽略**
     * 未知参数，用户以为开了思考其实没开。所以按host特判，未命中的才用通用写法。
     */
    private fun JsonObjectBuilder.applyReasoning(host: String, level: ThinkingLevel) {
        // AUTO = 不干预：一个参数都不发，让服务端/模型按自己的默认走。
        // 这样新对话（thinkingLevel未设置）不会因为塞了服务端不认的参数而400。
        if (level == ThinkingLevel.AUTO) return
        val on = level != ThinkingLevel.OFF
        when {
            // https://openrouter.ai/docs/use-cases/reasoning-tokens
            host.endsWith("openrouter.ai") -> put("reasoning", buildJsonObject {
                when (level) {
                    ThinkingLevel.OFF -> put("effort", "none")
                    ThinkingLevel.AUTO -> put("enabled", true)
                    else -> put("effort", level.effort ?: "medium")
                }
            })

            // 阿里云百炼
            host.endsWith("dashscope.aliyuncs.com") -> {
                put("enable_thinking", on)
                if (level != ThinkingLevel.AUTO) put("thinking_budget", level.budgetTokens)
            }

            // 火山方舟（豆包）
            host.endsWith("ark.cn-beijing.volces.com") ->
                put("thinking", buildJsonObject { put("type", if (on) "enabled" else "disabled") })

            // 智谱
            host.endsWith("open.bigmodel.cn") ->
                put("thinking", buildJsonObject { put("type", if (on) "enabled" else "disabled") })

            // 书生
            host.endsWith("chat.intern-ai.org.cn") -> put("thinking_mode", on)

            // 硅基流动
            host.endsWith("api.siliconflow.cn") -> {
                put("enable_thinking", on)
                if (level != ThinkingLevel.AUTO) put("thinking_budget", level.budgetTokens)
            }

            // DeepSeek
            host.endsWith("api.deepseek.com") -> {
                if (on) {
                    put("thinking", buildJsonObject { put("type", "enabled") })
                    level.effort?.let { put("reasoning_effort", it) }
                }
            }

            // 通用（o系列 / 其他兼容网关）
            else -> level.effort?.let { put("reasoning_effort", it) }
        }
    }

    private fun cacheControlOf(ttl: PromptCacheTtl): JsonObject = buildJsonObject {
        put("type", "ephemeral")
        ttl.apiValue?.let { put("ttl", it) }
    }

    /**
     * 在倒数第二条「非tool_result」的user消息的最后一个content block上打缓存断点。
     * 这是Anthropic多轮对话缓存的标准做法：前面的历史被缓存，最后一条保持新鲜。
     */
    private fun withMessagesCacheControl(msgs: JsonArray, ttl: PromptCacheTtl): JsonArray {
        val realUserIdx = msgs.mapIndexedNotNull { i, m ->
            val o = m as? JsonObject ?: return@mapIndexedNotNull null
            if (o["role"]?.jsonPrimitive?.contentOrNull != "user") return@mapIndexedNotNull null
            val arr = o["content"] as? JsonArray ?: return@mapIndexedNotNull null
            val isToolResult = arr.any {
                (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "tool_result"
            }
            if (isToolResult) null else i
        }
        if (realUserIdx.size < 2) return msgs
        val target = realUserIdx[realUserIdx.size - 2]
        return JsonArray(msgs.mapIndexed { i, m ->
            if (i != target) return@mapIndexed m
            val o = m.jsonObject
            val arr = o["content"] as? JsonArray ?: return@mapIndexed m
            val last = arr.lastIndex
            JsonObject(o + ("content" to JsonArray(arr.mapIndexed { j, b ->
                if (j == last) JsonObject(b.jsonObject + ("cache_control" to cacheControlOf(ttl)))
                else b
            })))
        })
    }
    private suspend fun listClaudeModels(provider: ProviderConfig): List<String> {
        val fallback = if (claudeNeedsV1Fallback(provider.baseUrl)) {
            claudeV1ModelsEndpoint(provider.baseUrl)
        } else null
        val text = try {
            claudeGetText(provider, claudeModelsEndpoint(provider.baseUrl))
        } catch (e: ClaudeEndpoint404) {
            claudeGetText(provider, fallback ?: throw e)
        }
        val parsed = runCatching {
            json.decodeFromString(ClaudeModelsResponse.serializer(), text)
        }.getOrNull()
        val ids = parsed?.data?.map { it.id }
        if (!ids.isNullOrEmpty()) return ids.distinct().sorted()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1] }.toList().distinct().sorted()
    }
    private suspend fun claudeGetText(provider: ProviderConfig, target: String): String {
        val resp = client.get(target) {
            headers {
                for ((k, v) in claudeAuthHeaders(provider)) append(k, v)
            }
        }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrDefault("")
            val msg = "HTTP ${resp.status.value}: ${err.take(300)}"
            if (resp.status == HttpStatusCode.NotFound) throw ClaudeEndpoint404(msg)
            throw RuntimeException(msg)
        }
        return resp.bodyAsText()
    }
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readClaudeStream(
        channel: ByteReadChannel
    ) {
        var usageInput = 0
        var usageOutput = 0
        val pendingTools = mutableMapOf<Int, PendingToolCall>()
        while (true) {
            val raw = channel.readUTF8Line() ?: break
            if (raw.isEmpty()) continue
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val field = raw.substring(0, colon).trim()
            val data = raw.substring(colon + 1).trim()
            if (field != "data" || data.isEmpty() || data == "[DONE]") continue
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val start = obj["content_block"]?.jsonObject ?: continue
                    val type = start["type"]?.jsonPrimitive?.contentOrNull
                    val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: continue
                    if (type == "tool_use") {
                        val id = start["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val name = start["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        pendingTools[idx] = PendingToolCall(id = id, name = name)
                    }
                }
                "content_block_delta" -> {
                    val delta = obj["delta"]?.jsonObject ?: continue
                    val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                            if (!text.isNullOrEmpty()) emit(StreamDelta(text = text))
                        }
                        "thinking_delta" -> {
                            val think = delta["thinking"]?.jsonPrimitive?.contentOrNull
                            if (!think.isNullOrEmpty()) emit(StreamDelta(reasoning = think))
                        }
                        "input_json_delta" -> {
                            val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: continue
                            val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                            pendingTools[idx]?.args?.append(partial)
                        }
                    }
                }
                "message_delta" -> {
                    val u = obj["usage"]?.jsonObject
                    if (u != null) {
                        usageOutput = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: usageOutput
                        if (usageInput > 0 || usageOutput > 0) {
                            emit(StreamDelta(usage = TokenUsage(
                                promptTokens = usageInput,
                                completionTokens = usageOutput,
                                totalTokens = usageInput + usageOutput
                            )))
                        }
                    }
                }
                "message_start" -> {
                    val msg = obj["message"]?.jsonObject
                    val u = msg?.get("usage")?.jsonObject
                    if (u != null) {
                        usageInput = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: usageInput
                        usageOutput = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: usageOutput
                    }
                }
                "error" -> {
                    val err = obj["error"]?.jsonObject
                    val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: obj.toString()
                    throw RuntimeException(msg.take(500))
                }
            }
        }
        if (pendingTools.isNotEmpty()) {
            emit(StreamDelta(toolCalls = pendingTools.values
                .filter { it.name.isNotBlank() }
                .map { ToolCallDelta(id = it.id, name = it.name, argumentsJson = it.args.toString()) }))
        }
    }
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readClaudeNonStream(
        response: io.ktor.client.statement.HttpResponse
    ) {
        val text = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw RuntimeException("Bad Claude response")
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "error") {
            val err = obj["error"]?.jsonObject
            throw RuntimeException(err?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(300))
        }
        val contentArr: JsonArray = obj["content"]?.jsonArray ?: buildJsonArray { }
        for (block in contentArr) {
            val bObj = block.jsonObject
            when (bObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> bObj["text"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotEmpty()) emit(StreamDelta(text = it))
                }
                "thinking" -> bObj["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotEmpty()) emit(StreamDelta(reasoning = it))
                }
                "tool_use" -> {
                    val id = bObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = bObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = bObj["input"] ?: kotlinx.serialization.json.JsonObject(emptyMap())
                    val argsJson = input.toString()
                    emit(StreamDelta(toolCalls = listOf(ToolCallDelta(id, name, argsJson))))
                }
            }
        }
        val usage = obj["usage"]?.jsonObject
        if (usage != null) {
            val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            emit(StreamDelta(usage = TokenUsage(
                promptTokens = input,
                completionTokens = output,
                totalTokens = input + output
            )))
        }
    }
}

internal fun claudeAuthHeaders(provider: ProviderConfig): List<Pair<String, String>> =
    buildList {
        if (provider.apiKey.isNotBlank()) add("x-api-key" to provider.apiKey)
        add("anthropic-version" to "2023-06-01")
        for ((k, v) in provider.customHeaders) {
            if (k.isBlank()) continue
            add(k to v)
        }
    }

internal class ClaudeEndpoint404(message: String) : RuntimeException(message)

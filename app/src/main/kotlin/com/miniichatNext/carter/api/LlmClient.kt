package com.miniichatNext.carter.api

import com.miniichatNext.carter.Debug.DebugLog
import com.miniichatNext.carter.data.AppSettings
import com.miniichatNext.carter.data.ProviderConfig
import com.miniichatNext.carter.data.ProviderType
import com.miniichatNext.carter.data.ThinkingLevel
import com.miniichatNext.carter.data.TokenUsage
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ChatPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
) {
    @Serializable
    data class ImageUrl(val url: String, val detail: String? = null)
}

data class ChatMessage(
    val role: String,
    val content: String,
    val parts: List<ChatPart>? = null
) {
    fun isMultipart(): Boolean = !parts.isNullOrEmpty()
}

data class StreamDelta(
    val text: String = "",
    val reasoning: String = "",
    val usage: TokenUsage? = null
)

@Serializable
private data class ChatChunk(
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        val role: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null
    )

    @Serializable
    data class ResponseMessage(val role: String = "assistant", val content: String = "")

    @Serializable
    data class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0
    )
}

@Serializable
private data class ChatResponse(
    val choices: List<ChatChunk.Choice> = emptyList(),
    val usage: ChatChunk.UsageDto? = null
)

@Serializable
private data class ModelEntry(val id: String)

@Serializable
private data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
private data class ClaudeModelEntry(
    val id: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
private data class ClaudeModelsResponse(val data: List<ClaudeModelEntry> = emptyList())

private fun claudeAuthHeaders(provider: ProviderConfig): List<Pair<String, String>> =
    buildList {
        if (provider.apiKey.isNotBlank()) add("x-api-key" to provider.apiKey)
        add("anthropic-version" to "2023-06-01")
        for ((k, v) in provider.customHeaders) {
            if (k.isBlank()) continue
            add(k to v)
        }
    }

private class ClaudeEndpoint404(message: String) : RuntimeException(message)

class LlmClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
    }

    private fun chatEndpoint(baseUrl: String, path: String = "/chat/completions") =
        "${baseUrl.trimEnd('/')}${if (path.startsWith("/")) path else "/$path"}"
    private fun modelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/models"
    private fun claudeMessagesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/messages"
    private fun claudeModelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/models"
    private fun claudeNeedsV1Fallback(baseUrl: String) = !baseUrl.trimEnd('/').endsWith("/v1")
    private fun claudeV1MessagesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/v1/messages"
    private fun claudeV1ModelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/v1/models"
    private fun responsesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/responses"

    private fun coerceToJson(v: String): JsonElement {
        if (v.equals("true", true)) return JsonPrimitive(true)
        if (v.equals("false", true)) return JsonPrimitive(false)
        v.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        v.toLongOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(v)
    }

    private fun buildOpenAiBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
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
                })
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", effTemp)
            put("messages", msgsJson)
            modelCfg?.maxTokens?.let { put("max_tokens", it) }
            for ((k, v) in provider.extraBody) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildResponseBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float
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
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", effTemp)
            put("input", input)
            modelCfg?.maxTokens?.let { put("max_output_tokens", it) }
            for ((k, v) in modelCfg?.extraBody ?: emptyMap()) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildClaudeBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float
    ): String {
        val modelCfg = provider.model(modelId)
        val effTemp = modelCfg?.temperature ?: temperature
        val effThinking = modelCfg?.thinking() ?: provider.thinking()
        val effMaxTokens = if (effThinking != ThinkingLevel.OFF) {
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
                    put("role", m.role)
                    if (m.isMultipart()) {
                        val parts = mutableListOf<JsonObject>()
                        if (m.content.isNotBlank()) {
                            parts.add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                        }
                        for (att in m.parts!!) {
                            val url = att.imageUrl?.url ?: continue
                            val match = Regex("^data:([^;]+);base64,(.+)$").matchEntire(url)
                            if (match != null) {
                                val mime = match.groupValues[1]
                                val data = match.groupValues[2]
                                parts.add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", mime)
                                        put("data", data)
                                    }
                                })
                            }
                        }
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            parts.forEach { add(it) }
                        })
                    } else {
                        put("content", m.content)
                    }
                })
            }
        }

        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("max_tokens", effMaxTokens)
            if (effThinking == ThinkingLevel.OFF) put("temperature", effTemp)
            put("messages", msgsJson)
            if (!systemText.isNullOrBlank()) put("system", systemText)
            if (effThinking != ThinkingLevel.OFF) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", effThinking.budgetTokens)
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
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildRequestBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float
    ): String {
        val modelCfg = provider.model(modelId)
        return when {
            provider.type() == ProviderType.CLAUDE ->
                buildClaudeBody(provider, modelId, messages, stream, temperature)
            modelCfg?.useResponseApi == true ->
                buildResponseBody(provider, modelId, messages, stream, temperature)
            else ->
                buildOpenAiBody(provider, modelId, messages, stream, temperature)
        }
    }

    suspend fun listModels(provider: ProviderConfig): List<String> = when (provider.type()) {
        ProviderType.CLAUDE -> listClaudeModels(provider)
        else -> listOpenAiModels(provider)
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

    fun chatStream(
        provider: ProviderConfig,
        settings: AppSettings,
        modelId: String,
        messages: List<ChatMessage>
    ): Flow<StreamDelta> = flow {
        val bodyText = buildRequestBody(
            provider = provider,
            modelId = modelId,
            messages = messages,
            stream = settings.stream,
            temperature = settings.temperature
        )

        val isClaude = provider.type() == ProviderType.CLAUDE
        val endpoint = when {
            isClaude -> claudeMessagesEndpoint(provider.baseUrl)
            provider.model(modelId)?.useResponseApi == true -> responsesEndpoint(provider.baseUrl)
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

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readOpenAiStream(
        channel: ByteReadChannel
    ) {
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isEmpty()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            val chunk = runCatching {
                json.decodeFromString(ChatChunk.serializer(), payload)
            }.getOrNull() ?: continue
            val delta = chunk.choices.firstOrNull()?.delta
            val text = delta?.content.orEmpty()
            val reasoning = delta?.reasoningContent.orEmpty()
            if (text.isNotEmpty() || reasoning.isNotEmpty()) {
                emit(StreamDelta(text = text, reasoning = reasoning))
            }
            chunk.usage?.let {
                emit(StreamDelta(usage = TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens
                )))
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readClaudeStream(
        channel: ByteReadChannel
    ) {
        var usageInput = 0
        var usageOutput = 0

        while (true) {
            val raw = channel.readUTF8Line() ?: break
            if (raw.isEmpty()) continue
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val field = raw.substring(0, colon).trim()
            val data = raw.substring(colon + 1).trim()
            // 只关心 data: 行；事件的类型在 JSON payload 的 "type" 字段里
            //（Claude 的 data JSON 自带 type，与 SSE event: 行同名，
            // 照 RikkaHub ClaudeStreamDecoder 按 payload type 分发）
            if (field != "data" || data.isEmpty() || data == "[DONE]") continue
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_delta" -> {
                    val delta = obj["delta"]?.jsonObject ?: continue
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                            if (!text.isNullOrEmpty()) emit(StreamDelta(text = text))
                        }
                        "thinking_delta" -> {
                            val think = delta["thinking"]?.jsonPrimitive?.contentOrNull
                            if (!think.isNullOrEmpty()) emit(StreamDelta(reasoning = think))
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
                // hello，EasterEgg文件content.7z密码：2705722903
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamDelta>.readOpenAiNonStream(
        response: io.ktor.client.statement.HttpResponse
    ) {
        val text = response.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ChatResponse.serializer(), text)
        }.getOrNull()
        val content = parsed?.choices?.firstOrNull()?.message?.content
        if (!content.isNullOrEmpty()) emit(StreamDelta(text = content))
        parsed?.usage?.let {
            emit(StreamDelta(usage = TokenUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )))
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

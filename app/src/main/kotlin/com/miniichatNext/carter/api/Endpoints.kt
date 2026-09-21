package com.miniichatNext.carter.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal fun chatEndpoint(baseUrl: String, path: String = "/chat/completions") =
    "${baseUrl.trimEnd('/')}${if (path.startsWith("/")) path else "/$path"}"

internal fun modelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/models"

internal fun claudeMessagesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/messages"

internal fun claudeModelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/models"

internal fun claudeNeedsV1Fallback(baseUrl: String) = !baseUrl.trimEnd('/').endsWith("/v1")

internal fun claudeV1MessagesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/v1/messages"

internal fun claudeV1ModelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/v1/models"

internal fun responsesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/responses"

internal fun coerceToJson(v: String): JsonElement {
    // 看起来像JSON对象/数组就按JSON解析，这样额外请求体也能写嵌套结构
    // （例如 {"thinking":{"type":"enabled"}}），非标网关可以自己适配
    val t = v.trim()
    if (t.startsWith("{") || t.startsWith("[")) {
        runCatching { return Json.parseToJsonElement(t) }
    }
    if (v.equals("true", true)) return JsonPrimitive(true)
    if (v.equals("false", true)) return JsonPrimitive(false)
    v.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    v.toLongOrNull()?.let { return JsonPrimitive(it) }
    return JsonPrimitive(v)
}

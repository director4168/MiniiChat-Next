/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.api

import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.util.AppJson
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class ImageGenerationClient {
    suspend fun generate(provider: ProviderConfig, model: String, prompt: String): String = runInterruptible(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Image prompt is required" }
        val base = provider.effectiveBaseUrl(model).trimEnd('/')
        val configured = provider.model(model)?.apiPath
            ?.takeIf { it.contains("/images/", true) && !it.contains("/edits", true) }
        val path = configured?.let { candidate ->
            if (candidate.startsWith("/v1/") && base.endsWith("/v1")) candidate.removePrefix("/v1") else candidate
        } ?: "/images/generations"
        val endpoint = chatEndpoint(base, path)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 300_000
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        val key = provider.effectiveApiKey(model)
        if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
        (provider.customHeaders + provider.model(model)?.customHeaders.orEmpty()).forEach { (name, value) ->
            if (name.isNotBlank()) connection.setRequestProperty(name, value)
        }
        val body = buildJsonObject {
            (provider.extraBody + provider.model(model)?.extraBody.orEmpty()).forEach { (name, value) ->
                if (name.isNotBlank()) put(name, coerceToJson(value))
            }
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            connection.outputStream.use { it.write(body) }
            val ok = connection.responseCode in 200..299
            val input = if (ok) connection.inputStream else connection.errorStream
            val raw = input?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val read = stream.read(bytes)
                    if (read < 0) break
                    require(output.size() + read <= 40 * 1024 * 1024) { "Image response is too large" }
                    output.write(bytes, 0, read)
                }
                output.toString("UTF-8")
            }.orEmpty()
            check(ok) { "Image server returned HTTP ${connection.responseCode}: ${raw.take(300)}" }
            val response = AppJson.json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid image response")
            (response["error"] as? JsonObject)?.let { error ->
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
                error("Image server error: ${message.take(300)}")
            }
            val defaultFormat = (response["output_format"] as? JsonPrimitive)?.contentOrNull
            (response["data"] as? JsonArray).orEmpty().joinToString("") { value ->
                val item = value as? JsonObject ?: return@joinToString ""
                val url = (item["url"] as? JsonPrimitive)?.contentOrNull
                    ?: (item["b64_json"] as? JsonPrimitive)?.contentOrNull?.let { encoded ->
                        val rawFormat = (item["output_format"] as? JsonPrimitive)?.contentOrNull ?: defaultFormat ?: "png"
                        val format = rawFormat.lowercase(Locale.ROOT).removePrefix("image/")
                            .takeIf { it in setOf("png", "jpeg", "jpg", "webp", "gif") } ?: "png"
                        "data:image/$format;base64,$encoded"
                    }
                url?.let(::imageMarkdown).orEmpty()
            }.also { check(it.isNotBlank()) { "Image response contains no image" } }
        } finally { connection.disconnect() }
    }
}

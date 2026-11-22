/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 把多模态输出转成有序Markdown，继续沿用正文的节点化持久化路径。 */
internal fun imageOutputMarkdown(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.joinToString("") { imageOutputMarkdown(it) }
    is JsonObject -> {
        val type = (value["type"] as? JsonPrimitive)?.contentOrNull
        when (type) {
            "text", "output_text" -> (value["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            "image_url", "image", "output_image" -> {
                val image = value["image_url"] ?: value["url"] ?: value["source"]
                val url = when (image) {
                    is JsonPrimitive -> image.contentOrNull
                    is JsonObject -> (image["url"] as? JsonPrimitive)?.contentOrNull
                        ?: (image["data"] as? JsonPrimitive)?.contentOrNull?.let { data ->
                            val mime = (image["media_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png"
                            "data:$mime;base64,$data"
                        }
                    else -> null
                } ?: (value["result"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
                    if (raw.startsWith("http://", true) || raw.startsWith("https://", true) ||
                        raw.startsWith("data:image/", true)) raw
                    else "data:image/png;base64,$raw"
                }
                url?.let(::imageMarkdown).orEmpty()
            }
            else -> ""
        }
    }
    else -> ""
}

internal fun imageMarkdown(url: String): String {
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true) && !url.startsWith("data:image/", true)) return ""
    return "\n\n![image](${url.replace("(", "%28").replace(")", "%29").replace("\n", "").replace("\r", "")})\n\n"
}

internal fun normalizeOpenAiImageOutput(element: JsonElement): JsonElement {
    val root = element as? JsonObject ?: return element
    val choices = root["choices"] as? JsonArray ?: return element
    return JsonObject(root + ("choices" to JsonArray(choices.map { choice ->
        val obj = choice as? JsonObject ?: return@map choice
        JsonObject(obj.mapValues { (key, value) ->
            if (key !in setOf("delta", "message") || value !is JsonObject) value else {
                val original = value["content"]
                val content = imageOutputMarkdown(original)
                val images = value["images"] as? JsonArray
                val imageText = images?.map(::imageOutputMarkdown).orEmpty()
                    .filter { it.isNotBlank() }
                    .distinct()
                    .filterNot { content.contains(it) }
                    .joinToString("")
                if (original is JsonArray || imageText.isNotEmpty()) {
                    JsonObject(value + ("content" to JsonPrimitive(content + imageText)))
                } else value
            }
        })
    })))
}

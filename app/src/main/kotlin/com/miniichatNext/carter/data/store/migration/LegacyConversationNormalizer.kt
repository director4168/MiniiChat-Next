/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store.migration

import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.MessageSegment
import com.miniichatNext.carter.data.model.ProviderOverride
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.model.ToolInvocationState
import com.miniichatNext.carter.util.AppJson
import java.util.UUID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal class LegacyConversationNormalizer {
    private val json = AppJson.json
    var issues = 0
        private set
    var skipped = 0
        private set

    fun markIssue() { issues++ }

    fun conversation(source: String, input: JsonElement?, keyId: String? = null): Conversation? {
        val objectValue = input as? JsonObject ?: return skipped()
        val bodyId = objectValue.text("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH }
        val id = keyId?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH } ?: bodyId ?: stableId(source)
        if (bodyId != null && bodyId != id) markIssue()
        val sourceMessages = array(objectValue["messages"])
        if (sourceMessages == null && objectValue["messages"] != null && objectValue["messages"] != JsonNull) return skipped()
        val reserved = sourceMessages.orEmpty().mapNotNull { (it as? JsonObject)?.text("id") }.toSet()
        val ids = HashSet<String>()
        val messages = sourceMessages.orEmpty().mapIndexedNotNull { index, value ->
            message("$source/message/$index", value, ids, reserved)
        }
        if (!sourceMessages.isNullOrEmpty() && messages.isEmpty()) return skipped()
        if (sourceMessages == null && objectValue.keys.none { it in CONVERSATION_FIELDS }) return skipped()
        return Conversation(
            id = id,
            title = (objectValue.text("title") ?: messages.firstOrNull { !it.hidden && it.content.isNotBlank() }
                ?.content?.take(30)?.replace('\n', ' ').orEmpty()),
            assistantId = objectValue.text("assistantId")?.takeIf { it.length <= MAX_ID_LENGTH }.orEmpty().ifBlank { "default" },
            messages = messages,
            chatSuggestionsByMsg = textLists(objectValue["chatSuggestionsByMsg"]),
            toolWorkspaceOverridden = objectValue.bool("toolWorkspaceOverridden") ?: false,
            toolWorkspaceId = objectValue.text("toolWorkspaceId"),
            toolMcpOverridden = objectValue.bool("toolMcpOverridden") ?: false,
            toolMcpEnabled = items(objectValue["toolMcpEnabled"]).mapNotNull(::text).toSet(),
            providerOverrides = (objectValue["providerOverrides"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                decode(ProviderOverride.serializer(), value)?.let { key to it }
            }.toMap(),
            modelProviderId = objectValue.text("modelProviderId"),
            modelId = objectValue.text("modelId"),
            thinkingLevel = objectValue.text("thinkingLevel"),
            createdAt = objectValue.timestamp("createdAt"),
            updatedAt = objectValue.timestamp("updatedAt"),
        )
    }

    private fun message(source: String, input: JsonElement, ids: MutableSet<String>, reserved: Set<String>): Message? {
        val obj = input as? JsonObject ?: text(input)?.let { JsonObject(mapOf("content" to JsonPrimitive(it))) }
            ?: return skipped()
        val parts = items(obj["orderedParts"]).mapNotNull { decode(MessageSegment.serializer(), it) }
        val attachments = items(obj["attachments"]).mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull skipped()
            val uri = item.text("uri")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull skipped()
            Attachment(
                id = item.text("id")?.takeIf { it.isNotBlank() } ?: stableId("$source/attachment/$index"),
                type = item.text("type") ?: "file",
                uri = uri,
                originalUri = item.text("originalUri").orEmpty(),
                persistedPermission = item.bool("persistedPermission") ?: false,
                mimeType = item.text("mimeType") ?: "application/octet-stream",
                name = item.text("name").orEmpty(),
                sizeBytes = item.timestamp("sizeBytes") ?: 0L,
                sha256 = item.text("sha256").orEmpty(),
            )
        }.let { list ->
            val reservedAttachments = list.mapTo(HashSet()) { it.id }
            val attachmentIds = HashSet<String>()
            list.mapIndexed { index, attachment ->
                if (attachmentIds.add(attachment.id)) attachment else {
                    markIssue()
                    attachment.copy(id = uniqueId("$source/attachment/duplicate/$index", attachmentIds, reservedAttachments))
                }
            }
        }
        val tools = items(obj["toolInvocations"]).mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull skipped()
            val rawState = item.text("state") ?: ToolInvocationState.EXECUTING
            val name = item.text("toolName") ?: return@mapIndexedNotNull skipped()
            ToolInvocation(
                callId = item.text("callId")?.takeIf { it.isNotBlank() } ?: stableId("$source/tool/$index"),
                toolName = name,
                arguments = item.rawText("arguments"),
                state = rawState,
                result = item.rawText("result"),
                error = item.text("error"),
                mcpBinding = item.text("mcpBinding"),
                historical = true,
            )
        }
        val role = obj.text("role")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH } ?: "legacy_unknown"
        val content = obj.text("content")
        val reasoning = obj.text("reasoningContent")
        if (content == null && reasoning == null && parts.isEmpty() && attachments.isEmpty() && tools.isEmpty()) return skipped()
        val oldId = obj.text("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH }
        val id = if (oldId != null && ids.add(oldId)) oldId else uniqueId(source, ids, reserved).also { markIssue() }
        if (role == "legacy_unknown") markIssue()
        return Message(
            id = id, role = role, content = content ?: parts.filterIsInstance<MessageSegment.Text>().joinToString("") { it.text },
            attachments = attachments, usage = usage(obj["usage"]), generationTimeMs = obj.timestamp("generationTimeMs"),
            lastUsage = usage(obj["lastUsage"]), stepCount = obj.number("stepCount"), finishedAt = obj.timestamp("finishedAt"),
            toolInvocations = tools, reasoningContent = reasoning, toolCallId = obj.text("toolCallId"),
            providerId = obj.text("providerId"), providerName = obj.text("providerName"), model = obj.text("model"),
            orderedParts = parts, hidden = obj.bool("hidden") ?: false, createdAt = obj.timestamp("createdAt"),
            editedAt = obj.timestamp("editedAt"), legacyImported = true,
        )
    }

    private fun usage(node: JsonElement?): TokenUsage? {
        if (node == null || node == JsonNull) return null
        val obj = node as? JsonObject ?: return skipped()
        val missing = items(obj["legacyMissingFields"]).mapNotNull(::text).toMutableSet()
        var present = false
        fun <T> pick(key: String, value: T?): T? {
            if (value == null) missing += key else if (key !in missing) present = true
            return value
        }
        fun count(key: String, default: Int = 0) = pick(key, obj.number(key)) ?: default
        fun flag(key: String) = pick(key, obj.bool(key)) ?: false
        val usage = TokenUsage(
            promptTokens = count("promptTokens"), completionTokens = count("completionTokens"), totalTokens = count("totalTokens"),
            durationMs = pick("durationMs", obj.timestamp("durationMs")) ?: 0L,
            tokensPerSecond = pick("tokensPerSecond", (obj["tokensPerSecond"] as? JsonPrimitive)?.floatOrNull
                ?.takeIf { it.isFinite() && it >= 0f }) ?: 0f,
            estimated = flag("estimated"), lastPromptTokens = obj.number("lastPromptTokens"),
            lastCompletionTokens = obj.number("lastCompletionTokens"), steps = count("steps", 1),
            cachedTokens = count("cachedTokens"), promptEstimated = flag("promptEstimated"),
            completionEstimated = flag("completionEstimated"), contextEstimated = flag("contextEstimated"),
            legacyMissingFields = missing.toSet(),
        )
        return usage.takeIf { present || it.lastPromptTokens != null || it.lastCompletionTokens != null }
    }

    private fun array(value: JsonElement?): JsonArray? = value as? JsonArray
    private fun items(value: JsonElement?): List<JsonElement> = array(value)
        ?: emptyList<JsonElement>().also { if (value != null && value != JsonNull) markIssue() }
    private fun textLists(value: JsonElement?): Map<String, List<String>> = (value as? JsonObject).orEmpty()
        .mapValues { (_, entry) -> items(entry).mapNotNull(::text) }
    private fun JsonObject.text(key: String): String? = text(this[key])
    private fun JsonObject.rawText(key: String): String = text(key)
        ?: this[key]?.takeUnless { it == JsonNull }?.toString().orEmpty()
    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
    private fun JsonObject.timestamp(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun <T> decode(serializer: DeserializationStrategy<T>, value: JsonElement): T? =
        try { json.decodeFromJsonElement(serializer, value) } catch (_: IllegalArgumentException) { skipped() }

    private fun <T> skipped(): T? { skipped++; return null }

    private fun uniqueId(source: String, used: MutableSet<String>, reserved: Set<String>): String {
        var suffix = 0
        while (true) {
            val id = stableId("$source/${suffix++}")
            if (id !in reserved && used.add(id)) return id
        }
    }

    companion object {
        private const val MAX_ID_LENGTH = 1024
        private val CONVERSATION_FIELDS = setOf("id", "title", "assistantId")
        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun stableId(source: String) = UUID.nameUUIDFromBytes("miniichat-conversations-v2:$source".toByteArray()).toString()
    }
}

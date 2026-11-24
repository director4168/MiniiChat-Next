/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.backup

import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.store.migration.LegacyConversationNormalizer
import com.miniichatNext.carter.data.store.migration.LegacyPreferencesMigration
import com.miniichatNext.carter.util.AppJson
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

internal object LegacyConversationBackup {
    const val MAX_BYTES = LegacyPreferencesMigration.MAX_BYTES
    val paths = setOf(
        "data/conversations.preferences_pb",
        "data/datastore/conversations.preferences_pb",
        "data/files/datastore/conversations.preferences_pb",
    )

    data class Decoded(val conversations: List<Conversation>, val skipped: Int)

    suspend fun read(file: File): Decoded {
        require(file.isFile && file.length() <= MAX_BYTES) { "Invalid conversation DataStore file" }
        val preferences = try {
            LegacyPreferencesMigration.read(file).asMap()
                .entries.associate { it.key.name to it.value }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid conversation DataStore file", error)
        }
        val perKey = preferences.entries.filter { it.key.startsWith("conv_") }.sortedBy { it.key }
        val legacy = preferences["conversations_json"]
        require(preferences.isEmpty() || perKey.isNotEmpty() || legacy != null) { "No conversation records in DataStore" }
        val older = (legacy as? String)?.let(AppJson::parseArrayOrNull)
        val decoder = LegacyConversationNormalizer()
        val recovered = linkedMapOf<String, Conversation>()
        var malformedContainer = 0
        for ((index, entry) in perKey.withIndex()) {
            val node = (entry.value as? String)?.let(AppJson::parse)
            decoder.conversation("backup/prefs/key/$index", node, entry.key.removePrefix("conv_"))?.let {
                recovered.putIfAbsent(it.id, it)
            }
        }
        if (older != null) {
            older.forEachIndexed { index, record ->
                decoder.conversation("backup/prefs/array/$index", record)?.let { recovered.putIfAbsent(it.id, it) }
            }
        } else if (legacy != null) malformedContainer++
        val skipped = decoder.skipped + malformedContainer
        require(recovered.isNotEmpty() || skipped == 0) { "No recoverable conversations in DataStore" }
        return Decoded(recovered.values.toList(), skipped)
    }

    fun readSnapshot(records: JsonArray): Decoded {
        val decoder = LegacyConversationNormalizer()
        val recovered = records.mapIndexedNotNull { index, record: JsonElement -> decoder.conversation("zip/$index", record) }
        require(records.isEmpty() || recovered.isNotEmpty()) { "No recoverable conversations in the backup" }
        return Decoded(recovered, decoder.skipped)
    }
}

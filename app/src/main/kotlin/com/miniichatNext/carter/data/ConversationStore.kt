package com.miniichatNext.carter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.conversationsDataStore: DataStore<Preferences> by preferencesDataStore(name = "conversations")

class ConversationStore(private val context: Context) {

    // Serialises read-modify-write cycles so a streaming flush and a user edit
    // cannot interleave and drop each other's messages.
    private val mutex = Mutex()

    private val key = stringPreferencesKey("conversations_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val conversationsFlow: Flow<List<Conversation>> = context.conversationsDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun snapshot(): List<Conversation> = conversationsFlow.first()

    suspend fun forAssistant(assistantId: String): List<Conversation> =
        snapshot().filter { it.assistantId == assistantId }

    suspend fun save(list: List<Conversation>) {
        context.conversationsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(Conversation.serializer()), list)
        }
    }

    suspend fun upsert(conv: Conversation) {
        mutex.withLock {
            val list = snapshot().toMutableList()
            val idx = list.indexOfFirst { it.id == conv.id }
            if (idx >= 0) list[idx] = conv else list.add(conv)
            save(list)
        }
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            save(snapshot().filterNot { it.id == id })
        }
    }

    suspend fun rename(id: String, title: String) {
        mutex.withLock {
            val list = snapshot().toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(title = title, updatedAt = System.currentTimeMillis())
                save(list)
            }
        }
    }

    /**
     * Atomically append messages to a conversation.
     * Used by the streaming pipeline so a background flush and a user edit can never
     * interleave and overwrite each other.
     */
    suspend fun appendMessages(convId: String, newMessages: List<Message>) {
        mutex.withLock {
            val list = snapshot().toMutableList()
            val idx = list.indexOfFirst { it.id == convId }
            if (idx < 0) return@withLock
            val conv = list[idx]
            list[idx] = conv.copy(
                messages = conv.messages + newMessages,
                updatedAt = System.currentTimeMillis()
            )
            save(list)
        }
    }

    /**
     * Atomically patch a conversation with [transform] under the same lock that
     * [appendMessages] uses, so partial updates cannot be lost.
     */
    suspend fun updateMessages(convId: String, transform: (List<Message>) -> List<Message>) {
        mutex.withLock {
            val list = snapshot().toMutableList()
            val idx = list.indexOfFirst { it.id == convId }
            if (idx < 0) return@withLock
            val conv = list[idx]
            list[idx] = conv.copy(
                messages = transform(conv.messages),
                updatedAt = System.currentTimeMillis()
            )
            save(list)
        }
    }

    suspend fun migrateAssistantId(defaultAssistantId: String) {
        val list = snapshot()
        val migrated = list.map {
            if (it.assistantId.isBlank()) it.copy(assistantId = defaultAssistantId) else it
        }
        if (migrated != list) save(migrated)
    }

    private fun decode(raw: String?): List<Conversation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Conversation.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}

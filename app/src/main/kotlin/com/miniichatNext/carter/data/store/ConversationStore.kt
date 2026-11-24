/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store

import android.content.Context
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConversationStore(context: Context) {
    private val database = ConversationDatabase.get(context)
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _active = MutableStateFlow<Conversation?>(null)
    private var observedId: String? = null
    val conversationsFlow: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    val activeConversation: StateFlow<Conversation?> = _active.asStateFlow()

    suspend fun observe(id: String?) = mutex.withLock {
        val next = io { id?.let(database::get) }
        observedId = id
        _active.value = next
    }

    suspend fun snapshot(): List<Conversation> = mutex.withLock {
        io { database.transaction { database.summaries().map { checkNotNull(database.get(it.id)) } } }
    }

    suspend fun get(id: String): Conversation? = mutex.withLock {
        _active.value?.takeIf { it.id == id } ?: io { database.transaction { database.get(id) } }
    }

    suspend fun owner(id: String, activeId: String?): String? = mutex.withLock {
        _active.value?.takeIf { conv -> conv.id == activeId && conv.messages.any { it.id == id } }?.id ?: io { database.owner(id) }
    }

    suspend fun forAssistant(assistantId: String): List<Conversation> = mutex.withLock {
        io { database.summaries(assistantId) }
    }

    suspend fun loadSummaries() = mutex.withLock { refresh() }

    suspend fun save(list: List<Conversation>) = mutex.withLock {
        require(list.map { it.id }.distinct().size == list.size)
        io {
            database.transaction {
                val nextIds = list.mapTo(HashSet()) { it.id }
                database.summaries().filterNot { it.id in nextIds }.forEach { database.delete(it.id) }
                list.forEach { database.putConversation(database.get(it.id), it) }
            }
        }
        refresh()
        _active.value = list.firstOrNull { it.id == observedId }
    }

    internal suspend fun restoreLegacyBackup(
        list: List<Conversation>?,
        preferences: Map<String, Map<String, SqlPreference>>,
        replace: Boolean,
    ) = mutex.withLock {
        require(list == null || list.map { it.id }.distinct().size == list.size)
        io {
            database.transaction {
                if (list != null) {
                    if (replace) {
                        val nextIds = list.mapTo(HashSet()) { it.id }
                        database.summaries().filterNot { it.id in nextIds }.forEach { database.delete(it.id) }
                    }
                    list.forEach { conv ->
                        if (replace || !database.exists(conv.id)) {
                            database.putConversation(database.get(conv.id), conv)
                            check(database.get(conv.id) == conv)
                        }
                    }
                }
                for ((namespace, imported) in preferences) {
                    val before = database.readPreferences(namespace)
                    val after = if (replace) imported else imported + before
                    database.writePreferences(namespace, before, after)
                    check(database.readPreferences(namespace).asPreferences().encodePreferences() == after)
                }
                database.verifyIntegrity()
            }
            if (preferences.isNotEmpty()) database.notifyPreferencesChanged()
            observedId?.let(database::get)
        }.also { active ->
            refresh()
            _active.value = active
        }
        Unit
    }

    suspend fun upsert(conv: Conversation) {
        updateConversation(conv.id, create = { conv }) { conv }
    }

    suspend fun updateConversation(
        id: String,
        create: (() -> Conversation)? = null,
        transform: (Conversation) -> Conversation,
    ): Conversation? = mutex.withLock {
        val current = _active.value?.takeIf { it.id == id }
        val updated = io {
            database.transaction {
                val previous = current ?: database.get(id)
                val base = previous ?: create?.invoke() ?: return@transaction null
                val next = transform(base)
                require(next.id == id)
                if (next == previous) return@transaction previous
                next.copy(updatedAt = System.currentTimeMillis()).also { database.putConversation(previous, it) }
            }
        }
        if (updated != null) publish(updated)
        updated
    }

    suspend fun updateMessage(conversationId: String, messageId: String, transform: (Message) -> Message) = mutex.withLock {
        val change = io {
            database.transaction {
                val message = database.message(conversationId, messageId) ?: return@transaction null
                val next = transform(message)
                require(next.id == messageId)
                if (next == message) return@transaction null
                val updatedAt = System.currentTimeMillis()
                database.putMessage(conversationId, message, next)
                database.touch(conversationId, updatedAt)
                next to updatedAt
            }
        } ?: return@withLock
        _active.value?.takeIf { it.id == conversationId }?.let { active ->
            _active.value = active.copy(updatedAt = change.second, messages = active.messages.map {
                if (it.id == messageId) change.first else it
            })
        }
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) it.copy(updatedAt = change.second) else it
        }.sortedWith(summaryOrder)
    }

    suspend fun delete(id: String) = mutex.withLock {
        io { database.transaction { database.delete(id) } }
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (observedId == id) _active.value = null
    }

    suspend fun rename(id: String, title: String) { updateConversation(id) { it.copy(title = title) } }

    suspend fun appendMessages(convId: String, newMessages: List<Message>) {
        updateMessages(convId) { it + newMessages }
    }

    suspend fun updateMessages(convId: String, transform: (List<Message>) -> List<Message>) {
        updateConversation(convId) { it.copy(messages = transform(it.messages)) }
    }

    suspend fun migrateAssistantId(defaultAssistantId: String) {
        for (conv in _conversations.value.filter { it.assistantId.isBlank() }) {
            updateConversation(conv.id) { if (it.assistantId.isBlank()) it.copy(assistantId = defaultAssistantId) else it }
        }
    }

    suspend fun hasMigrationWarnings(): Boolean = mutex.withLock { io { database.state("warnings") == "1" } }

    private fun publish(conversation: Conversation) {
        if (observedId == conversation.id) _active.value = conversation
        val summary = conversation.copy(messages = emptyList(), chatSuggestionsByMsg = emptyMap())
        _conversations.value = (_conversations.value.filterNot { it.id == summary.id } + summary).sortedWith(summaryOrder)
    }

    private suspend fun refresh() {
        _conversations.value = io { database.summaries().map { summary ->
            (database.metadata(summary.id) ?: summary).copy(updatedAt = summary.updatedAt, chatSuggestionsByMsg = emptyMap())
        } }
    }

    private suspend fun <T> io(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                database.requireReady()
                block()
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        private val summaryOrder = compareByDescending<Conversation> { it.updatedAt }.thenBy { it.id }
    }
}

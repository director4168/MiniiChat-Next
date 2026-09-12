package com.miniichatNext.carter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.conversationsDataStore: DataStore<Preferences> by preferencesDataStore(name = "conversations")

/**
 * 会话存储
 *
 * 存储布局：**每个会话一个 preference key**（`conv_<id>`），而不是把所有会话塞进一个JSON大blob
 * 流式回复每800ms就会flush一次，单会话写入因此只需encode那一个会话
 *
 * 读取侧用内存缓存（[MutableStateFlow]）作为UI数据源，避免每次flush都重新解码全部会话；
 * 缓存由本类独占维护（所有写入都经过这里的mutex），所以不会读到陈旧数据
 */
class ConversationStore(private val context: Context) {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val legacyKey = stringPreferencesKey(LEGACY_KEY)

    private val _cache = MutableStateFlow<List<Conversation>>(emptyList())
    val conversationsFlow: Flow<List<Conversation>> = _cache.asStateFlow()

    @Volatile
    private var hydrated = false

    private fun keyFor(id: String) = stringPreferencesKey(KEY_PREFIX + id)

    // ---------- read ----------

    suspend fun snapshot(): List<Conversation> = mutex.withLock {
        hydrateLocked()
        _cache.value
    }

    suspend fun forAssistant(assistantId: String): List<Conversation> =
        snapshot().filter { it.assistantId == assistantId }

    /** 首次访问时把磁盘内容读进缓存，并把旧的单key格式迁移成per-conversation key */
    private suspend fun hydrateLocked() {
        if (hydrated) return
        val prefs = context.conversationsDataStore.data.first()

        val legacyRaw = prefs[legacyKey]
        val loaded: List<Conversation> = if (legacyRaw != null) {
            val migrated = decodeList(legacyRaw)
            com.miniichatNext.carter.Debug.DebugLog.i(
                "Store", "conversation.migrate legacy blob -> per-key, count=${migrated.size}"
            )
            context.conversationsDataStore.edit { p ->
                migrated.forEach { conv ->
                    p[keyFor(conv.id)] = encodeOne(conv)
                }
                p.remove(legacyKey)
            }
            migrated
        } else {
            readPerConversation(prefs)
        }
        _cache.value = loaded
        hydrated = true
    }

    private fun readPerConversation(prefs: Preferences): List<Conversation> =
        prefs.asMap().entries
            .filter { it.key.name.startsWith(KEY_PREFIX) }
            .mapNotNull { (_, value) -> (value as? String)?.let(::decodeOne) }

    // ---------- write ----------

    /** 全量替换（慎用，会重写每个会话的key） */
    suspend fun save(list: List<Conversation>) = mutex.withLock {
        hydrateLocked()
        val previousIds = _cache.value.map { it.id }.toSet()
        val nextIds = list.map { it.id }.toSet()
        context.conversationsDataStore.edit { p ->
            (previousIds - nextIds).forEach { p.remove(keyFor(it)) }
            list.forEach { conv -> p[keyFor(conv.id)] = encodeOne(conv) }
        }
        _cache.value = list
    }

    suspend fun upsert(conv: Conversation) = mutex.withLock {
        hydrateLocked()
        com.miniichatNext.carter.Debug.DebugLog.d(
            "Store",
            "conversation.upsert id=${conv.id} msgs=${conv.messages.size} " +
                "assistant=${conv.assistantId} title=${conv.title}"
        )
        context.conversationsDataStore.edit { p -> p[keyFor(conv.id)] = encodeOne(conv) }
        val cur = _cache.value.toMutableList()
        val idx = cur.indexOfFirst { it.id == conv.id }
        if (idx >= 0) cur[idx] = conv else cur.add(conv)
        _cache.value = cur
    }

    suspend fun delete(id: String) = mutex.withLock {
        hydrateLocked()
        context.conversationsDataStore.edit { p -> p.remove(keyFor(id)) }
        _cache.value = _cache.value.filterNot { it.id == id }
    }

    suspend fun rename(id: String, title: String) = mutex.withLock {
        hydrateLocked()
        val cur = _cache.value
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return@withLock
        val updated = cur[idx].copy(title = title, updatedAt = System.currentTimeMillis())
        context.conversationsDataStore.edit { p -> p[keyFor(id)] = encodeOne(updated) }
        _cache.value = cur.toMutableList().also { it[idx] = updated }
    }

    /** 原子追加消息（与[updateMessages]共用同一把key，避免流式flush与用户编辑互相覆盖） */
    suspend fun appendMessages(convId: String, newMessages: List<Message>) =
        updateMessages(convId) { it + newMessages }

    /** 原子改写某个会话的消息列表；只重写该会话自己的key */
    suspend fun updateMessages(convId: String, transform: (List<Message>) -> List<Message>) =
        mutex.withLock {
            hydrateLocked()
            val cur = _cache.value
            val idx = cur.indexOfFirst { it.id == convId }
            if (idx < 0) return@withLock
            val conv = cur[idx]
            val updated = conv.copy(
                messages = transform(conv.messages),
                updatedAt = System.currentTimeMillis()
            )
            context.conversationsDataStore.edit { p -> p[keyFor(convId)] = encodeOne(updated) }
            _cache.value = cur.toMutableList().also { it[idx] = updated }
        }

    suspend fun migrateAssistantId(defaultAssistantId: String) {
        val list = snapshot()
        val migrated = list.map {
            if (it.assistantId.isBlank()) it.copy(assistantId = defaultAssistantId) else it
        }
        if (migrated != list) save(migrated)
    }

    // ---------- codec ----------

    private fun encodeOne(conv: Conversation): String =
        json.encodeToString(Conversation.serializer(), conv)

    private fun decodeOne(raw: String): Conversation? =
        runCatching { json.decodeFromString(Conversation.serializer(), raw) }.getOrNull()

    private fun decodeList(raw: String?): List<Conversation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Conversation.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY_PREFIX = "conv_"
        private const val LEGACY_KEY = "conversations_json"
    }
}

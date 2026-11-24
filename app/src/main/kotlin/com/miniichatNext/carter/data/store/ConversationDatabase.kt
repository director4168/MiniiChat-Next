/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.util.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ConversationDatabase private constructor(
    context: Context,
    name: String = NAME,
    private val reader: SQLiteDatabase? = null,
) : SQLiteOpenHelper(context, name, null, 2, DatabaseErrorHandler { }) {
    private val json = AppJson.json
    internal val preferenceChanges = MutableStateFlow(0L)

    init {
        if (reader == null) {
            if (name == NAME) setWriteAheadLoggingEnabled(true)
            else if (Build.VERSION.SDK_INT >= 28) {
                setOpenParams(SQLiteDatabase.OpenParams.Builder()
                    .setJournalMode("DELETE")
                    .setSynchronousMode("FULL")
                    .build())
            }
        }
    }

    private val readDb: SQLiteDatabase get() = reader ?: readableDatabase

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE conversations (
            id TEXT PRIMARY KEY NOT NULL, assistant_id TEXT NOT NULL, title TEXT NOT NULL,
            created_at_ms INTEGER, updated_at_ms INTEGER)""")
        db.execSQL("CREATE INDEX conversation_assistant_date ON conversations(assistant_id, updated_at_ms, id)")
        db.execSQL("""CREATE TABLE messages (
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            id TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, hidden INTEGER NOT NULL,
            created_at_ms INTEGER, edited_at_ms INTEGER, finished_at_ms INTEGER, generation_time_ms INTEGER,
            step_count INTEGER, PRIMARY KEY(conversation_id, id), UNIQUE(conversation_id, position))""")
        db.execSQL("CREATE INDEX message_id ON messages(id)")
        db.execSQL("""CREATE TABLE conversation_payload (
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            chunk INTEGER NOT NULL, value BLOB NOT NULL, PRIMARY KEY(conversation_id, chunk))""")
        for (table in MESSAGE_TABLES) {
            db.execSQL("""CREATE TABLE $table (
                conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, kind TEXT NOT NULL,
                chunk INTEGER NOT NULL, value BLOB NOT NULL,
                PRIMARY KEY(conversation_id, message_id, kind, chunk),
                FOREIGN KEY(conversation_id, message_id) REFERENCES messages(conversation_id, id) ON DELETE CASCADE)""")
        }
        db.execSQL("CREATE TABLE migration_state (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE migration_issues (source TEXT PRIMARY KEY NOT NULL, issues INTEGER NOT NULL, skipped INTEGER NOT NULL)")
        createPreferences(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createPreferences(db)
        else error("Missing conversation database migration: $oldVersion → $newVersion")
    }

    private fun createPreferences(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE app_preferences (
            namespace TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
            chunk INTEGER NOT NULL, value BLOB NOT NULL, PRIMARY KEY(namespace, name, chunk))""")
    }

    fun readPreferences(namespace: String): Map<String, SqlPreference> = readDb.rawQuery(
        "SELECT name,type,value FROM app_preferences WHERE namespace=? ORDER BY name,chunk", arrayOf(namespace)
    ).use { cursor ->
        buildMap {
            var name: String? = null
            var type = ""
            val bytes = java.io.ByteArrayOutputStream()
            fun finish() { name?.let { put(it, SqlPreference(type, bytes.toString(Charsets.UTF_8.name()))) } }
            while (cursor.moveToNext()) {
                val key = checkNotNull(cursor.getString(0))
                if (name != key) {
                    finish()
                    name = key
                    type = checkNotNull(cursor.getString(1))
                    bytes.reset()
                }
                bytes.write(cursor.getBlob(2))
            }
            finish()
        }
    }

    fun writePreferences(namespace: String, before: Map<String, SqlPreference>, after: Map<String, SqlPreference>) {
        for (key in before.keys + after.keys) {
            val value = after[key]
            if (value == before[key]) continue
            writableDatabase.delete("app_preferences", "namespace=? AND name=?", arrayOf(namespace, key))
            if (value == null) continue
            val columns = ContentValues().apply {
                put("namespace", namespace)
                put("name", key)
                put("type", value.type)
            }
            if (value.value.isEmpty()) writableDatabase.insertOrThrow("app_preferences", null, ContentValues(columns).apply {
                put("chunk", 0)
                put("value", byteArrayOf())
            }) else writeChunks("app_preferences", columns, value.value)
        }
    }

    fun notifyPreferencesChanged() { preferenceChanges.update { it + 1 } }

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    fun requireReady() = check(state("ready") == "1") { "Conversation database is not ready" }

    fun state(name: String): String? = readDb.rawQuery(
        "SELECT value FROM migration_state WHERE name=?", arrayOf(name)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setState(name: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO migration_state(name,value) VALUES(?,?)", arrayOf(name, value))
    }

    fun clearStaging() {
        check(state("ready") != "1")
        writableDatabase.delete("conversations", null, null)
        writableDatabase.delete("migration_state", null, null)
        writableDatabase.delete("migration_issues", null, null)
    }

    fun issue(source: String, issues: Int, skipped: Int) {
        if (issues == 0 && skipped == 0) return
        writableDatabase.execSQL("INSERT INTO migration_issues(source,issues,skipped) VALUES(?,?,?)", arrayOf<Any>(source, issues, skipped))
    }

    fun issues(): List<String> = readDb.rawQuery(
        "SELECT source, issues, skipped FROM migration_issues ORDER BY source LIMIT 200", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add("${cursor.getString(0)}: ${cursor.getInt(1)} / ${cursor.getInt(2)}")
    } }

    fun verify(expectedConversations: Long, expectedMessages: Long) {
        val db = readDb
        fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
        check(count("conversations") == expectedConversations && count("messages") == expectedMessages)
        verifyIntegrity()
    }

    fun verifyIntegrity() {
        val db = readDb
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        db.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
    }

    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use {
            if (!it.moveToFirst() || it.getInt(0) != 0) throw java.io.IOException("Conversation WAL checkpoint did not complete")
        }
    }

    fun summaries(assistantId: String? = null): List<Conversation> {
        val filter = if (assistantId == null) "" else " WHERE assistant_id=?"
        return readDb.rawQuery(
            "SELECT id, assistant_id, title, created_at_ms, updated_at_ms FROM conversations$filter ORDER BY updated_at_ms DESC, id",
            assistantId?.let { arrayOf(it) }
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Conversation(
                id = checkNotNull(cursor.getString(0)), assistantId = checkNotNull(cursor.getString(1)), title = checkNotNull(cursor.getString(2)),
                createdAt = cursor.longOrNull(3), updatedAt = cursor.longOrNull(4),
            ))
        } }
    }

    fun exists(id: String): Boolean = readDb.rawQuery(
        "SELECT 1 FROM conversations WHERE id=?", arrayOf(id)
    ).use { it.moveToFirst() }

    fun metadata(id: String): Conversation? = readChunks("conversation_payload", "conversation_id=?", arrayOf(id))
        ?.let { entry ->
            val meta = json.decodeFromString(Conversation.serializer(), entry)
            readDb.rawQuery("SELECT updated_at_ms FROM conversations WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) meta.copy(updatedAt = it.longOrNull(0)) else null
            }
        }

    fun get(id: String): Conversation? = metadata(id)?.copy(messages = readDb.rawQuery(
        "SELECT $MESSAGE_COLUMNS FROM messages WHERE conversation_id=? ORDER BY position", arrayOf(id)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readMessage(id, cursor)) } })

    fun message(conversationId: String, id: String): Message? = readDb.rawQuery(
        "SELECT $MESSAGE_COLUMNS FROM messages WHERE conversation_id=? AND id=?", arrayOf(conversationId, id)
    ).use { if (it.moveToFirst()) readMessage(conversationId, it) else null }

    fun owner(id: String): String? = readDb.rawQuery(
        "SELECT conversation_id FROM messages WHERE id=? LIMIT 2", arrayOf(id)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).takeUnless { cursor.moveToNext() } else null }

    fun putConversation(previous: Conversation?, next: Conversation) {
        val metadata = next.copy(messages = emptyList())
        if (previous?.copy(messages = emptyList()) != metadata) putMetadata(previous != null, metadata)
        val old = previous?.messages.orEmpty()
        val oldById = old.withIndex().associateBy { it.value.id }
        val nextIds = next.messages.mapTo(HashSet()) { it.id }
        check(nextIds.size == next.messages.size) { "Duplicate message ID" }
        old.asSequence().filter { it.id !in nextIds }.forEach {
            writableDatabase.delete("messages", "conversation_id=? AND id=?", arrayOf(next.id, it.id))
        }
        val reordered = next.messages.withIndex().filter { (index, message) ->
            oldById[message.id]?.index?.let { it != index } == true
        }
        reordered.forEach { (index, message) ->
            writableDatabase.execSQL("UPDATE messages SET position=? WHERE conversation_id=? AND id=?", arrayOf<Any>(-index - 1, next.id, message.id))
        }
        next.messages.forEachIndexed { index, message ->
            val prior = oldById[message.id]
            if (prior == null || prior.value != message || prior.index != index) putMessage(next.id, prior?.value, message, index)
        }
    }

    fun putMetadata(exists: Boolean, next: Conversation) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", next.id)
            put("assistant_id", next.assistantId)
            put("title", next.title.take(4096))
            put("created_at_ms", next.createdAt)
            put("updated_at_ms", next.updatedAt)
        }
        if (exists) check(db.update("conversations", values, "id=?", arrayOf(next.id)) == 1)
        else db.insertOrThrow("conversations", null, values)
        db.delete("conversation_payload", "conversation_id=?", arrayOf(next.id))
        writeChunks("conversation_payload", ContentValues().apply { put("conversation_id", next.id) },
            json.encodeToString(Conversation.serializer(), next.copy(messages = emptyList())))
    }

    fun putMessage(conversationId: String, previous: Message?, next: Message, position: Int? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("conversation_id", conversationId)
            put("id", next.id)
            position?.let { put("position", it) }
            put("role", next.role)
            put("hidden", next.hidden)
            put("created_at_ms", next.createdAt)
            put("finished_at_ms", next.finishedAt)
            put("edited_at_ms", next.editedAt)
            put("generation_time_ms", next.generationTimeMs)
            put("step_count", next.stepCount)
        }
        if (previous != null) check(db.update("messages", values, "conversation_id=? AND id=?", arrayOf(conversationId, next.id)) == 1)
        else db.insertOrThrow("messages", null, values)
        val current = json.encodeToJsonElement(Message.serializer(), next) as JsonObject
        val prior = previous?.let { json.encodeToJsonElement(Message.serializer(), it) as JsonObject }
        for ((key, value) in current) {
            if (key in BASIC_FIELDS || (prior != null && prior[key] == value)) continue
            val table = messageTable(key)
            db.delete(table, "conversation_id=? AND message_id=? AND kind=?", arrayOf(conversationId, next.id, key))
            if (value != JsonNull) writeChunks(table, ContentValues().apply {
                put("conversation_id", conversationId); put("message_id", next.id); put("kind", key)
            }, value.toString())
        }
    }

    fun touch(id: String, updatedAt: Long) {
        writableDatabase.execSQL("UPDATE conversations SET updated_at_ms=? WHERE id=?", arrayOf<Any>(updatedAt, id))
    }

    fun delete(id: String) {
        writableDatabase.delete("conversations", "id=?", arrayOf(id))
    }

    private fun readMessage(conversationId: String, cursor: Cursor): Message {
        val id = checkNotNull(cursor.getString(0))
        val fields = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id), "role" to JsonPrimitive(cursor.getString(1)),
            "hidden" to JsonPrimitive(cursor.getInt(2) != 0), "content" to JsonPrimitive("")
        )
        for ((index, key) in TIMED_FIELDS.withIndex()) fields[key] = JsonPrimitive(cursor.longOrNull(index + 3))
        for (table in MESSAGE_TABLES) {
            readDb.rawQuery(
                "SELECT kind,value FROM $table WHERE conversation_id=? AND message_id=? ORDER BY kind,chunk",
                arrayOf(conversationId, id)
            ).use { payload ->
                val bytes = java.io.ByteArrayOutputStream()
                var key: String? = null
                fun finish() { key?.let { fields[it] = json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())) } }
                while (payload.moveToNext()) {
                    val nextKey = checkNotNull(payload.getString(0))
                    if (key != nextKey) { finish(); bytes.reset(); key = nextKey }
                    bytes.write(payload.getBlob(1))
                }
                finish()
            }
        }
        return json.decodeFromJsonElement(Message.serializer(), JsonObject(fields))
    }

    private fun writeChunks(table: String, key: ContentValues, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = (offset + CHUNK_BYTES).coerceAtMost(bytes.size)
            writableDatabase.insertOrThrow(table, null, ContentValues(key).apply {
                put("chunk", index++)
                put("value", bytes.copyOfRange(offset, end))
            })
            offset = end
        }
    }

    private fun readChunks(table: String, where: String, args: Array<String>): String? = readDb.rawQuery(
        "SELECT value FROM $table WHERE $where ORDER BY chunk", args
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val bytes = java.io.ByteArrayOutputStream()
        do { bytes.write(cursor.getBlob(0)) } while (cursor.moveToNext())
        bytes.toString(Charsets.UTF_8.name())
    }

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun messageTable(field: String): String = when (field) {
        "usage", "lastUsage" -> "message_usage"
        "orderedParts" -> "message_parts"
        "toolInvocations" -> "tool_calls"
        "attachments" -> "message_attachments"
        else -> "message_payload"
    }

    companion object {
        const val NAME = "conversations.db"
        private const val CHUNK_BYTES = 128 * 1024
        private const val MESSAGE_COLUMNS = "id,role,hidden,created_at_ms,edited_at_ms,finished_at_ms,generation_time_ms,step_count"
        private val TIMED_FIELDS = listOf("createdAt", "editedAt", "finishedAt", "generationTimeMs", "stepCount")
        private val BASIC_FIELDS = TIMED_FIELDS.toSet() + setOf("id", "role", "hidden")
        private val MESSAGE_TABLES = listOf("message_payload", "message_usage", "message_parts", "tool_calls", "message_attachments")

        internal const val BACKUP_ID = 0x4d434e42

        fun createBackup(context: Context, file: java.io.File): ConversationDatabase {
            require(!file.exists() && file.parentFile?.isDirectory == true)
            return ConversationDatabase(context, file.absolutePath)
        }

        fun <T> readBackup(context: Context, file: java.io.File, read: (ConversationDatabase) -> T): T {
            require(file.isFile && file.length() >= 100) { "Invalid backup database" }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun number(sql: String) = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getInt(0) }
                require(number("PRAGMA application_id") == BACKUP_ID && number("PRAGMA user_version") == 2) { "Unsupported backup database" }
                val expected = setOf("conversations", "messages", "conversation_payload", "app_preferences", "migration_state", "migration_issues") + MESSAGE_TABLES
                val tables = db.rawQuery("SELECT name,type,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' AND name != 'android_metadata'", null).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) when (cursor.getString(1)) {
                            "table" -> {
                                require(cursor.getString(2).orEmpty().startsWith("CREATE TABLE ", ignoreCase = true))
                                add(checkNotNull(cursor.getString(0)))
                            }
                            "index" -> Unit
                            else -> error("Unexpected backup database object")
                        }
                    }
                }
                require(tables == expected) { "Unexpected backup database schema" }
                val source = ConversationDatabase(context, file.absolutePath, db)
                source.verifyIntegrity()
                return read(source)
            }
        }

        private val instances = java.util.WeakHashMap<Context, ConversationDatabase>()

        fun get(context: Context): ConversationDatabase = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) { ConversationDatabase(app) }
        }
    }
}

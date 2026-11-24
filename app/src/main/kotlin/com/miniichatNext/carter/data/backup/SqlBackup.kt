/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.backup

import android.content.Context
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.store.ConversationDatabase
import com.miniichatNext.carter.data.store.SqlPreference
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.deleteTreeNoFollow
import com.miniichatNext.carter.util.publishFile
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject

internal object SqlBackup {
    const val PATH = "data/backup.db"
    const val FORMAT = 4
    private val json = AppJson.json

    fun write(context: Context, file: File, snapshot: BackupSnapshot) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create backup directory" }
        require(!file.exists()) { "Backup database already exists" }
        val staging = Files.createTempDirectory(parent.toPath(), ".sqlite-export-").toFile()
        try {
            val source = File(staging, "working.db")
            writeDatabase(context, source, snapshot)

            // 只回读主文件，避免旁文件掩盖尚未写入主库的数据。
            val verified = Files.createDirectory(staging.toPath().resolve("verify")).resolve("backup.db").toFile()
            source.copyTo(verified)
            verifyStandalone(context, verified, snapshot)
            publishFile(verified, file, overwrite = false)
        } finally {
            deleteTreeNoFollow(staging)
        }
    }

    private fun writeDatabase(context: Context, file: File, snapshot: BackupSnapshot) {
        val database = ConversationDatabase.createBackup(context, file)
        try {
            database.writableDatabase.disableWriteAheadLogging()
            database.writableDatabase.execSQL("PRAGMA application_id=${ConversationDatabase.BACKUP_ID}")
            database.transaction {
                snapshot.conversations.forEach { database.putConversation(null, it) }
                fun rows(namespace: String, entries: Map<String, String>) {
                    database.writePreferences(namespace, emptyMap(), entries.mapValues { SqlPreference("string", it.value) })
                }
                val settings = json.encodeToJsonElement(AppSettings.serializer(), snapshot.settings) as JsonObject
                rows("settings", settings.mapValues { it.value.toString() })
                rows("providers", snapshot.providers.mapIndexed { index, value -> index.toString() to json.encodeToString(ProviderConfig.serializer(), value) }.toMap())
                rows("assistants", snapshot.assistants.mapIndexed { index, value -> index.toString() to json.encodeToString(Assistant.serializer(), value) }.toMap())
                val profile = json.encodeToJsonElement(UserProfile.serializer(), snapshot.profile) as JsonObject
                rows("user_profile", profile.mapValues { it.value.toString() })
                val mcp = json.parseToJsonElement(snapshot.mcp) as JsonObject
                rows("mcp_configs", mcp.mapValues { it.value.toString() })
                rows("theme", mapOf("overrides" to snapshot.theme))
                database.verify(snapshot.conversations.size.toLong(), snapshot.conversations.sumOf { it.messages.size.toLong() })
            }
        } finally { database.close() }
    }

    private fun verifyStandalone(context: Context, file: File, expected: BackupSnapshot) {
        val header = ByteArray(20)
        file.inputStream().use { java.io.DataInputStream(it).readFully(header) }
        check(header[18] == 1.toByte() && header[19] == 1.toByte()) { "Backup database still uses a WAL header" }
        val actual = read(context, file)
        check(actual.conversations.associateBy { it.id } == expected.conversations.associateBy { it.id } &&
            actual.providers == expected.providers && actual.assistants == expected.assistants &&
            actual.profile == expected.profile && actual.theme == expected.theme &&
            actual.settings == expected.settings.copy(updateNodesInvalid = false) &&
            json.parseToJsonElement(actual.mcp) == json.parseToJsonElement(expected.mcp)
        ) { "Backup database verification failed" }
    }

    fun read(context: Context, file: File): BackupSnapshot = ConversationDatabase.readBackup(context, file) { db ->
        fun rows(namespace: String) = db.readPreferences(namespace).mapValues { (_, value) ->
            require(value.type == "string") { "Invalid backup field" }
            value.value
        }
        fun fields(namespace: String) = JsonObject(rows(namespace).mapValues { json.parseToJsonElement(it.value) })
        fun ordered(namespace: String): List<String> = rows(namespace).let { values ->
            List(values.size) { requireNotNull(values[it.toString()]) { "Invalid backup record order" } }
        }
        val conversations = db.summaries().map { checkNotNull(db.get(it.id)) { "Missing backup conversation metadata" } }
        val settings = fields("settings")
        val profile = fields("user_profile")
        val mcp = fields("mcp_configs")
        require(settings.isNotEmpty() && profile.isNotEmpty() && mcp.isNotEmpty()) { "Missing backup database configuration" }
        db.verify(conversations.size.toLong(), conversations.sumOf { it.messages.size.toLong() })
        BackupSnapshot(
            settings = json.decodeFromJsonElement(AppSettings.serializer(), settings),
            providers = ordered("providers").map { json.decodeFromString(ProviderConfig.serializer(), it) },
            assistants = ordered("assistants").map { json.decodeFromString(Assistant.serializer(), it) },
            conversations = conversations,
            profile = json.decodeFromJsonElement(UserProfile.serializer(), profile),
            mcp = mcp.toString(),
            theme = requireNotNull(rows("theme")["overrides"]),
            conversationFormat = 2,
        )
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store.migration

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.miniichatNext.carter.data.store.ConversationDatabase
import com.miniichatNext.carter.data.store.asPreferences
import com.miniichatNext.carter.data.store.encodePreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

internal object LegacyPreferencesMigration {
    val namespaces = listOf("settings", "providers", "assistants", "user_profile", "mcp_configs", "workspaces", "skills")
    const val MAX_BYTES = 128L * 1024 * 1024

    private fun source(context: Context, namespace: String) = File(context.filesDir, "datastore/$namespace.preferences_pb")

    fun pending(context: Context, db: ConversationDatabase): Boolean = namespaces.any {
        source(context, it).exists() && db.state("preferences:$it") != "retained"
    }

    suspend fun read(file: File): Preferences {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= MAX_BYTES) { "Invalid legacy Preferences DataStore" }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try { PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).data.first() }
        finally {
            job.cancel()
            withContext(NonCancellable) { job.join() }
        }
    }

    suspend fun migrate(context: Context, db: ConversationDatabase) {
        for (namespace in namespaces) {
            currentCoroutineContext().ensureActive()
            val file = source(context, namespace)
            val key = "preferences:$namespace"
            if (!file.exists() || db.state(key) == "retained") continue
            val digest = legacyDigest(file)
            val existing = db.state(key)
            if (existing != null && existing != digest) {
                db.transaction {
                    db.setState(key, "retained")
                    db.setState("warnings", "1")
                    db.setState("acknowledged", "0")
                    db.setState("warningsCount", ((db.state("warningsCount")?.toLongOrNull() ?: 0) + 1).toString())
                    db.issue("preferences_source_changed/$namespace", 1, 0)
                }
                continue
            }
            if (existing == null) {
                val before = db.readPreferences(namespace)
                val legacy = read(file).encodePreferences()
                val combined = legacy + before
                check(legacyDigest(file) == digest) { "Legacy preferences changed during migration" }
                db.transaction {
                    db.writePreferences(namespace, before, combined)
                    check(db.readPreferences(namespace).asPreferences().encodePreferences() == combined)
                    db.verifyIntegrity()
                    db.setState(key, digest)
                }
                db.notifyPreferencesChanged()
            }
            db.verifyIntegrity()
            db.checkpoint()
            check(legacyDigest(file) == digest) { "Legacy preferences changed before cleanup" }
            Files.deleteIfExists(file.resolveSibling(file.name + ".tmp").toPath())
            Files.deleteIfExists(file.toPath())
        }
    }
}

internal fun legacyDigest(file: File): String {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            hash.update(bytes, 0, count)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

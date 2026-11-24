/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store.migration

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.annotation.StringRes
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.ConversationDatabase
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private val Context.legacyConversations by preferencesDataStore(name = "conversations")

internal object ConversationMigration {
    const val LEGACY_PATH = "datastore/conversations.preferences_pb"
    const val WORK_DIRECTORY = "conversation-migration"

    enum class Phase(@StringRes val label: Int) {
        CHECKING(R.string.migration_checking),
        REQUIRED(R.string.migration_preparing),
        STARTING(R.string.migration_preparing),
        READING(R.string.migration_reading),
        WRITING(R.string.migration_writing),
        VERIFYING(R.string.migration_verifying),
        CLEANING(R.string.migration_cleaning),
        CONFIGURATION(R.string.migration_configuration),
        READY(R.string.migration_done),
        WARNINGS(R.string.migration_warnings),
        FAILED(R.string.migration_failed),
    }

    data class Status(
        val phase: Phase = Phase.CHECKING,
        val processed: Int = 0,
        val total: Int = 0,
        val conversations: Long = 0,
        val messages: Long = 0,
        val warnings: Long = 0,
        val skipped: Long = 0,
        val legacyRemoved: Boolean = false,
        @StringRes val detail: Int? = null,
    )

    private val lock = Mutex()
    private val status = MutableStateFlow(Status())
    val flow = status.asStateFlow()

    fun claimService(): Boolean = status.value.let { current ->
        current.phase == Phase.REQUIRED && status.compareAndSet(current, current.copy(phase = Phase.STARTING))
    }

    fun retry() {
        val current = status.value
        if (current.phase == Phase.FAILED) status.compareAndSet(current, Status())
    }

    suspend fun report(context: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching { ConversationDatabase.get(context).issues() }.getOrDefault(emptyList())
    }

    suspend fun acknowledge(context: Context) = lock.withLock {
        val current = status.value
        if (current.phase != Phase.WARNINGS) return@withLock
        withContext(Dispatchers.IO) {
            try {
                ConversationDatabase.get(context).setState("acknowledged", "1")
            } catch (error: SQLiteFullException) {
                DebugLog.w("Migration", "Reminder state was not saved: ${error.javaClass.simpleName}")
            }
        }
        status.value = current.copy(phase = Phase.READY)
    }

    fun fail(@StringRes detail: Int) {
        status.value = status.value.copy(phase = Phase.FAILED, detail = detail)
    }

    suspend fun probe(context: Context) = lock.withLock {
        if (status.value.phase != Phase.CHECKING) return@withLock
        try {
            withContext(Dispatchers.IO) {
                val db = ConversationDatabase.get(context)
                if (db.state("ready") == "1") {
                    status.value = if (needsCleanup(db) || LegacyPreferencesMigration.pending(context, db)) Status(Phase.REQUIRED) else publishedStatus(db, context)
                } else if (workDir(context).resolve("published").exists()) {
                    fail(R.string.migration_invalid)
                } else if (legacy(context).exists() || db.state("source") != null || LegacyPreferencesMigration.pending(context, db)) {
                    status.value = Status(Phase.REQUIRED)
                } else {
                    db.transaction { db.verify(0, 0); db.setState("ready", "1") }
                    markPublished(context)
                    status.value = Status(Phase.READY)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.w("Migration", "Probe failed: ${error.javaClass.simpleName}")
            fail(if (error is SQLiteFullException) R.string.migration_no_space else R.string.migration_invalid)
        }
    }

    suspend fun run(context: Context) = lock.withLock {
        try {
            val complete = withContext(Dispatchers.IO) {
                val app = context.applicationContext
                migrate(app)
                val db = ConversationDatabase.get(app)
                if (LegacyPreferencesMigration.pending(app, db)) {
                    status.value = status.value.copy(phase = Phase.CONFIGURATION)
                    LegacyPreferencesMigration.migrate(app, db)
                }
                publishedStatus(db, app)
            }
            status.value = complete
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.w("Migration", "Migration interrupted: ${error.javaClass.simpleName}")
            fail(when (error) {
                is CleanupFailed -> R.string.migration_cleanup_failed
                is SQLiteFullException, is NotEnoughSpace -> R.string.migration_no_space
                else -> R.string.migration_invalid
            })
        }
    }

    private suspend fun migrate(context: Context): Status {
        val db = ConversationDatabase.get(context)
        if (db.state("ready") == "1") return finalizePublished(db, context)
        status.value = Status(Phase.READING)
        val file = legacy(context)
        if (!file.exists() && db.state("source") == null) {
            db.transaction { db.verify(0, 0); db.setState("ready", "1") }
            return publishedStatus(db, context)
        }
        check(file.isFile) { "Legacy database missing" }
        val source = legacyDigest(file)
        val copy = workDir(context).resolve("legacy-$source.preferences_pb")
        if (!copy.exists() || legacyDigest(copy) != source) {
            if (copy.parentFile?.let { !it.isDirectory && !it.mkdirs() } == true) error("Could not create migration directory")
            if (file.length() >= copy.parentFile!!.usableSpace) throw NotEnoughSpace()
            val stage = File(copy.parentFile, "legacy.tmp")
            try {
                stage.outputStream().use { out -> file.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                check(legacyDigest(stage) == source && stage.renameTo(copy)) { "Could not retain legacy source" }
            } finally { stage.delete() }
        }
        val preferences = context.legacyConversations.data.first().asMap().entries
            .associate { it.key.name to it.value }
        check(legacyDigest(file) == source) { "Legacy source changed" }
        val perKey = preferences.entries.filter { it.key.startsWith("conv_") }.sortedBy { it.key }
        val legacyRaw = preferences["conversations_json"]
        val older = (legacyRaw as? String)?.let(AppJson::parseArrayOrNull)
        val units = perKey.size + (older?.size ?: if (legacyRaw != null) 1 else 0)
        if (db.state("source") != source || db.state("algorithm") != "2") db.transaction {
            db.clearStaging()
            db.setState("source", source)
            db.setState("algorithm", "2")
        }
        val resume = db.state("processed")?.toIntOrNull() ?: 0
        var processed = 0
        var conversations = db.state("conversations")?.toLongOrNull() ?: 0
        var messages = db.state("messages")?.toLongOrNull() ?: 0
        var warnings = db.state("warningsCount")?.toLongOrNull() ?: 0
        var skipped = db.state("skipped")?.toLongOrNull() ?: 0

        fun publish(phase: Phase) = Status(phase, processed, units, conversations, messages, warnings, skipped)
        suspend fun unit(location: String, node: () -> JsonElement?, key: String? = null) {
            currentCoroutineContext().ensureActive()
            if (++processed <= resume) return
            val decoder = LegacyConversationNormalizer()
            val record = decoder.conversation(location, node(), key)
            db.transaction {
                if (record != null) {
                    if (db.exists(record.id)) {
                        decoder.markIssue()
                    } else {
                        db.putConversation(null, record)
                        check(db.get(record.id) == record) { "Conversation changed while migrating" }
                        conversations++
                        messages += record.messages.size
                    }
                }
                warnings += decoder.issues
                skipped += decoder.skipped
                db.issue(location, decoder.issues, decoder.skipped)
                db.setState("processed", processed.toString())
                db.setState("conversations", conversations.toString())
                db.setState("messages", messages.toString())
                db.setState("warningsCount", warnings.toString())
                db.setState("skipped", skipped.toString())
            }
            status.value = publish(Phase.WRITING)
        }

        status.value = publish(Phase.WRITING).copy(processed = resume)
        for ((index, entry) in perKey.withIndex()) {
            unit("conv_key/$index", { (entry.value as? String)?.let(AppJson::parse) }, entry.key.removePrefix("conv_"))
        }
        if (older != null) {
            for ((index, item) in older.withIndex()) unit("conversations_json/$index", { item })
        } else if (legacyRaw != null) unit("conversations_json", { null })
        check(units == 0 || conversations > 0) { "No recoverable conversations" }
        check(legacyDigest(file) == source) { "Legacy source changed" }
        currentCoroutineContext().ensureActive()
        status.value = publish(Phase.VERIFYING)
        db.transaction {
            db.verify(conversations, messages)
            db.setState("warnings", if (skipped > 0 || warnings > 0) "1" else "0")
            db.setState("ready", "1")
        }
        return finalizePublished(db, context)
    }

    private fun needsCleanup(db: ConversationDatabase): Boolean =
        db.state("source") != null && db.state("skipped") == "0" && db.state("legacyCleanup") == null

    private suspend fun finalizePublished(db: ConversationDatabase, context: Context): Status {
        if (!needsCleanup(db)) return publishedStatus(db, context)
        currentCoroutineContext().ensureActive()
        status.value = status.value.copy(phase = Phase.CLEANING)
        markPublished(context)
        val source = checkNotNull(db.state("source"))
        check(source.length == 64 && source.all { it in "0123456789abcdef" })
        val file = legacy(context)
        try {
            if (file.exists() && (!file.isFile || legacyDigest(file) != source)) {
                db.transaction {
                    db.setState("legacyCleanup", "retained")
                    db.setState("warnings", "1")
                    db.setState("warningsCount", ((db.state("warningsCount")?.toLongOrNull() ?: 0) + 1).toString())
                    db.setState("acknowledged", "0")
                    db.issue("source_changed_after_publish", 1, 0)
                }
                return publishedStatus(db, context)
            }
            db.verifyIntegrity()
            db.checkpoint()
            val dir = workDir(context)
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(file.resolveSibling(file.name + ".tmp").toPath())
            Files.deleteIfExists(dir.resolve("legacy-$source.preferences_pb").toPath())
            Files.deleteIfExists(dir.resolve("legacy.tmp").toPath())
            db.setState("legacyCleanup", "done")
        } catch (error: IOException) {
            throw CleanupFailed(error)
        }
        return publishedStatus(db, context)
    }

    private fun publishedStatus(db: ConversationDatabase, context: Context): Status {
        markPublished(context)
        return if (db.state("warnings") == "1" && db.state("acknowledged") != "1") {
            Status(Phase.WARNINGS,
                conversations = db.state("conversations")?.toLongOrNull() ?: 0,
                messages = db.state("messages")?.toLongOrNull() ?: 0,
                warnings = db.state("warningsCount")?.toLongOrNull() ?: 0,
                skipped = db.state("skipped")?.toLongOrNull() ?: 0,
                legacyRemoved = db.state("legacyCleanup") == "done",
            )
        } else Status(Phase.READY)
    }

    private fun legacy(context: Context) = File(context.filesDir, LEGACY_PATH)
    private fun workDir(context: Context) = File(context.noBackupFilesDir, WORK_DIRECTORY)

    private fun markPublished(context: Context) {
        val dir = workDir(context)
        if (!dir.isDirectory) check(dir.mkdirs() || dir.isDirectory)
        dir.resolve("published").createNewFile()
    }

    private class CleanupFailed(cause: IOException) : Exception(cause)
    private class NotEnoughSpace : Exception()
}

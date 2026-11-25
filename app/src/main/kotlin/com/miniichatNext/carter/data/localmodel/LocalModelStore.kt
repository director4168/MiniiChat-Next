/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderType
import com.miniichatNext.carter.util.AppJson
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object LocalModelStore {
    const val PROVIDER_ID = "@local-gguf"
    val supported: Boolean get() = Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS
    val modelAccess = Mutex()
    private val changes = MutableStateFlow<List<LocalModelRecord>>(emptyList())
    val records = changes.asStateFlow()
    private val lock = Mutex()
    private var initialized = false

    fun root(context: Context) = File(context.filesDir, "local_models")

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (initialized) return@withLock
            val root = root(context)
            root.listFiles()?.filter { it.name.startsWith(".import-") || it.name.startsWith(".delete-") }
                ?.forEach { if (Files.isSymbolicLink(it.toPath())) it.delete() else it.deleteRecursively() }
            reload(root)
            initialized = true
        }
    }

    fun provider(name: String, records: List<LocalModelRecord>): ProviderConfig? =
        records.takeIf { supported && it.isNotEmpty() }?.let { models ->
            ProviderConfig(PROVIDER_ID, name, "", "", providerType = ProviderType.LOCAL.name, createdAt = 0L,
                models = models.map { record ->
                    ModelConfig(modelId = record.id, displayName = record.name, contextLength = record.parameters.contextSize,
                        temperature = record.parameters.temperature, maxTokens = record.parameters.maxTokens,
                        supportsReasoning = false, supportsToolCall = false, supportsImageInput = false)
                })
        }

    fun weights(context: Context, record: LocalModelRecord) = File(root(context), "${record.id}/model.gguf")

    suspend fun publish(context: Context, directory: File, record: LocalModelRecord) = lock.withLock {
        val root = root(context)
        val existing = changes.value.firstOrNull { it.sha256 == record.sha256 }
        if (existing != null) return@withLock existing
        write(directory, record)
        check(directory.renameTo(File(root, record.id)))
        reload(root)
        record
    }

    suspend fun update(context: Context, id: String, name: String, parameters: LocalModelParameters) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && parameters.isValid())
        lock.withLock {
            val record = changes.value.first { it.id == id }
            val root = root(context)
            write(File(root, id), record.copy(name = name.trim(), parameters = parameters))
            reload(root)
        }
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        modelAccess.withLock {
            lock.withLock {
                val record = changes.value.firstOrNull { it.id == id } ?: return@withLock
                val root = root(context)
                val hidden = File(root, ".delete-${record.id}")
                check(File(root, record.id).renameTo(hidden))
                reload(root)
                hidden.deleteRecursively()
            }
        }
    }

    private fun reload(root: File) {
        changes.value = root.listFiles().orEmpty().mapNotNull { directory ->
            val id = directory.name
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory || runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return@mapNotNull null
            runCatching {
                val metadata = AtomicFile(File(directory, "metadata.json"))
                val record = AppJson.jsonCompact.decodeFromString<LocalModelRecord>(metadata.readFully().decodeToString())
                val weights = File(directory, "model.gguf")
                record.takeIf { it.id == id && it.parameters.isValid() && !Files.isSymbolicLink(weights.toPath()) &&
                    weights.isFile && weights.length() == it.size }
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    private fun write(directory: File, record: LocalModelRecord) {
        val metadata = AtomicFile(File(directory, "metadata.json"))
        val bytes = AppJson.jsonCompact.encodeToString(LocalModelRecord.serializer(), record).encodeToByteArray()
        val output = metadata.startWrite()
        try {
            output.write(bytes)
            metadata.finishWrite(output)
        } catch (failure: Exception) {
            metadata.failWrite(output)
            throw failure
        }
    }
}

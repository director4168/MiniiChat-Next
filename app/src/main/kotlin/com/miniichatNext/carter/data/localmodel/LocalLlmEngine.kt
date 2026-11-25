/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import android.app.ActivityManager
import android.content.Context
import com.miniichatNext.carter.R
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.ChatPart
import com.miniichatNext.carter.api.StreamDelta
import com.miniichatNext.carter.data.model.TokenUsage
import java.nio.file.Files
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock

internal class LocalLlmEngine(private val context: Context) {
    fun chatStream(modelId: String, messages: List<ChatMessage>, stream: Boolean) = channelFlow {
        check(LocalModelStore.supported) { context.getString(R.string.local_model_unsupported) }
        LocalModelStore.modelAccess.withLock {
            val record = LocalModelStore.records.value.firstOrNull { it.id == modelId }
                ?: error(context.getString(R.string.local_model_missing))
            val file = LocalModelStore.weights(context, record)
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == record.size) {
                context.getString(R.string.local_model_missing)
            }
            val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            check(!info.lowMemory && record.size <= info.totalMem * 3 / 4) { context.getString(R.string.local_model_memory) }
            val turns = messages.filter { it.role in setOf("system", "user", "assistant") }.mapNotNull { message ->
                val text = message.parts?.filterIsInstance<ChatPart.Text>()?.joinToString("\n") { it.text }
                    ?.takeIf { it.isNotBlank() } ?: message.content
                text.takeIf { it.isNotBlank() }?.let { message.role to it.replace('\u0000', ' ') }
            }
            check(turns.any { it.first == "user" }) { context.getString(R.string.local_model_text_required) }
            val output = LocalUtf8Output()
            val whole = if (stream) null else StringBuilder()
            val started = System.nanoTime()
            val sink = LocalTokenSink { bytes ->
                val text = output.append(bytes)
                when {
                    whole != null -> { whole.append(text); true }
                    text.isEmpty() -> true
                    else -> trySendBlocking(StreamDelta(eventReceived = true, text = text)).isSuccess
                }
            }
            val counts = runNative(file.absolutePath, turns, record.parameters, sink)
            check(counts[0] == 0) {
                context.getString(when (counts[0]) {
                    1 -> R.string.local_model_load_failed
                    2 -> R.string.local_model_context_exceeded
                    else -> R.string.local_model_generate_failed
                })
            }
            val remaining = output.finish()
            val text = whole?.append(remaining)?.toString() ?: remaining
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            send(StreamDelta(eventReceived = true, text = text, usage = TokenUsage(
                promptTokens = counts[1], completionTokens = counts[2], totalTokens = counts[1] + counts[2],
                durationMs = elapsed, lastPromptTokens = counts[1], lastCompletionTokens = counts[2],
            )))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun runNative(path: String, turns: List<Pair<String, String>>, params: LocalModelParameters,
        sink: LocalTokenSink): IntArray = suspendCancellableCoroutine { continuation ->
        val lock = Any()
        var handle = try {
            LlamaJni.newCancellation()
        } catch (e: LinkageError) {
            continuation.resumeWithException(IllegalStateException(context.getString(R.string.local_model_load_failed), e))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            synchronized(lock) { if (handle != 0L) LlamaJni.cancel(handle) }
        }
        try {
            val counts = LlamaJni.generate(handle, path.encodeToByteArray(), turns.map { it.first }.toTypedArray(),
                turns.map { it.second.encodeToByteArray() }.toTypedArray(), params.contextSize, params.threads,
                params.maxTokens, params.temperature, params.topP, params.topK, params.repeatPenalty, params.seed, sink)
            continuation.resume(counts)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        } catch (e: LinkageError) {
            continuation.resumeWithException(IllegalStateException(context.getString(R.string.local_model_load_failed), e))
        } finally {
            synchronized(lock) { LlamaJni.freeCancellation(handle); handle = 0L }
        }
    }
}

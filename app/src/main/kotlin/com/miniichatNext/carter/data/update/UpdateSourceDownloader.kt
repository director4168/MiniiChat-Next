/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
/*
 * Adapted from AAswordman/Operit: GithubReleaseUtil.kt / UpdateSourceDownloader.kt.
 * Copyright Operit contributors. Upstream LGPL-3.0-only terms and notices are retained. See AGPL_NOTICE.md / assets/licenses/Operit-update-NOTICE.txt.
 * Changes: HttpURLConnection, bounded probes, single-stream transfer, cancellation,
 * strict response validation and caller-supplied APK verification.
 */
package com.miniichatNext.carter.data.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class UpdateSourceDownloader {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        connections.toList().forEach { runCatching { it.disconnect() } }
    }

    private fun connection(url: String, timeout: Int): HttpURLConnection {
        checkActive()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "MiniiChat-Next-Updater")
        }
        connections.add(conn)
        if (cancelled) { conn.disconnect(); connections.remove(conn); throw CancellationException() }
        return conn
    }

    private fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Update cancelled")
    }

    private fun release(conn: HttpURLConnection) {
        connections.remove(conn)
        conn.disconnect()
    }

    suspend fun probe(
        sources: List<UpdateSource>,
        onStarted: (UpdateSource) -> Unit = {},
        onResult: (UpdateSource, Int, Int) -> Unit,
    ): List<UpdateSource> = coroutineScope {
        val semaphore = Semaphore(4)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        sources.map { source -> async(Dispatchers.IO) {
            semaphore.withPermit {
                currentCoroutineContext().ensureActive()
                onStarted(source)
                val result = try {
                    runInterruptible {
                        val first = probeOne(source)
                        val second = probeOne(source)
                        source.copy(speedBytesPerSec = minOf(first.speedBytesPerSec, second.speedBytesPerSec),
                            elapsedMs = maxOf(first.elapsedMs, second.elapsedMs),
                            latencyMs = maxOf(first.latencyMs ?: first.elapsedMs, second.latencyMs ?: second.elapsedMs))
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { source.copy(error = failure.message ?: failure.javaClass.simpleName) }
                onResult(result, completed.incrementAndGet(), sources.size)
                result
            }
        } }.awaitAll()
    }

    private fun probeOne(source: UpdateSource): UpdateSource {
        val started = System.nanoTime()
        val conn = connection(source.url, 2500)
        try {
            conn.setRequestProperty("Range", "bytes=0-65535")
            val code = conn.responseCode
            val latencyMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            require(code == 200 || code == 206) { "HTTP $code" }
            require(conn.contentType?.startsWith("text/", true) != true) { "Not a binary download" }
            var count = 0L
            val first = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (count < 65536) {
                    checkActive()
                    if ((System.nanoTime() - started) / 1_000_000 > 5000) break
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), 65536 - count).toInt())
                    if (read < 0) break
                    if (count < 4) first.write(buffer, 0, minOf(read, (4 - count).toInt()))
                    count += read
                }
            }
            val magic = first.toByteArray()
            require(count > 0 && magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()) { "Not an APK/ZIP response" }
            val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            return source.copy(speedBytesPerSec = count * 1000 / elapsed, elapsedMs = elapsed, latencyMs = latencyMs)
        } finally { release(conn) }
    }

    /** 完整APK只请求选中的一个源；失败由用户重试，绝不遍历其余候选。 */
    suspend fun download(
        source: UpdateSource,
        target: File,
        onProgress: (UpdateTransferProgress) -> Unit,
        verify: suspend (File) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        checkActive()
        try {
            runInterruptible { transfer(source.url, target, onProgress) }
            currentCoroutineContext().ensureActive()
            try {
                verify(target)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { throw UpdateVerificationFailure(target, failure) }
            target
        } catch (failure: UpdateVerificationFailure) { throw failure }
        catch (cancel: CancellationException) { target.delete(); throw cancel }
        catch (failure: Exception) {
            target.delete()
            throw IOException("${source.key}: ${failure.message ?: failure.javaClass.simpleName}", failure)
        }
    }

    private fun transfer(url: String, target: File, onProgress: (UpdateTransferProgress) -> Unit) {
        val conn = connection(url, 15000)
        try {
            val code = conn.responseCode
            require(code == 200) { "HTTP $code" }
            require(conn.contentType?.startsWith("text/", true) != true) { "Not a binary download" }
            val length = conn.contentLengthLong
            require(length <= 512L * 1024 * 1024) { "APK exceeds download limit" }
            target.parentFile?.mkdirs()
            var readTotal = 0L
            var lastBytes = 0L
            var lastAt = System.nanoTime()
            onProgress(UpdateTransferProgress(0, length, 0))
            conn.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    readTotal += count
                    require(readTotal <= 512L * 1024 * 1024) { "APK exceeds download limit" }
                    output.write(buffer, 0, count)
                    val elapsed = (System.nanoTime() - lastAt) / 1_000_000
                    if (elapsed >= 300) {
                        onProgress(UpdateTransferProgress(readTotal, length, (readTotal - lastBytes) * 1000 / elapsed))
                        lastAt = System.nanoTime()
                        lastBytes = readTotal
                    }
                }
            } }
            require(readTotal > 0 && (length < 0 || readTotal == length)) { "Incomplete APK download" }
            onProgress(UpdateTransferProgress(readTotal, length, 0))
        } finally { release(conn) }
    }
}

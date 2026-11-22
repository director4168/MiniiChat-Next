/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.mcp

import java.io.Closeable
import java.io.IOException
import java.io.PushbackReader
import java.net.HttpURLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class McpSseStream(private val connection: HttpURLConnection) : Closeable {
    data class Event(val name: String, val data: String)

    private val queue = ArrayBlockingQueue<Event>(16)
    private val closed = AtomicBoolean()
    @Volatile private var finished = false
    @Volatile private var failure: Exception? = null
    val isOpen: Boolean get() = !finished && !closed.get()

    private val reader = Thread({
        try {
            PushbackReader(connection.inputStream.bufferedReader(Charsets.UTF_8)).use { input ->
                var name = "message"
                val data = StringBuilder()
                var hasData = false
                var firstLine = true
                while (!closed.get()) {
                    val raw = input.readBoundedLine() ?: break
                    val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
                    firstLine = false
                    if (line.isEmpty()) {
                        if (hasData && !queue.offer(Event(name, data.toString()))) throw IOException("MCP SSE queue limit reached")
                        data.setLength(0)
                        hasData = false
                        name = "message"
                        continue
                    }
                    val field = line.substringBefore(':')
                    val value = line.substringAfter(':', "").removePrefix(" ")
                    when (field) {
                        "event" -> name = value.ifEmpty { "message" }
                        "data" -> {
                            if (hasData) data.append('\n')
                            if (data.length + value.length > MAX_EVENT_CHARS) throw IOException("MCP SSE event exceeds size limit")
                            data.append(value)
                            hasData = true
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) failure = error
        } finally {
            finished = true
            connection.disconnect()
        }
    }, "MiniiChat-McpSse").apply { isDaemon = true; start() }

    fun next(deadlineNs: Long): Event {
        while (!closed.get()) {
            failure?.let { throw it }
            queue.poll()?.let { return it }
            if (finished) throw IOException("MCP SSE reached end of stream")
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0) throw IOException("MCP SSE response timed out")
            queue.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)?.let { return it }
        }
        throw IOException("MCP SSE connection closed")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            reader.interrupt()
            connection.disconnect()
        }
    }

    private fun PushbackReader.readBoundedLine(): String? {
        val line = StringBuilder()
        while (true) {
            val ch = read()
            if (ch < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (ch == 10) return line.toString()
            if (ch == 13) {
                val next = read()
                if (next >= 0 && next != 10) unread(next)
                return line.toString()
            }
            if (line.length >= MAX_EVENT_CHARS) throw IOException("MCP SSE line exceeds size limit")
            line.append(ch.toChar())
        }
    }

    private companion object { const val MAX_EVENT_CHARS = 512 * 1024 }
}

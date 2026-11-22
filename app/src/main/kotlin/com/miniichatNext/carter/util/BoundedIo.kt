/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class InputLimitExceeded() : IOException("Input exceeds the size limit")

internal fun InputStream.copyBounded(output: OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val remaining = maxBytes - total
        val readSize = if (remaining >= buffer.size) buffer.size else remaining.toInt() + 1
        val count = read(buffer, 0, readSize)
        if (count < 0) return total
        if (count > maxBytes - total) throw InputLimitExceeded()
        output.write(buffer, 0, count)
        total += count
    }
}

internal fun InputStream.readBounded(maxBytes: Int): ByteArray = ByteArrayOutputStream().use { output ->
    copyBounded(output, maxBytes.toLong())
    output.toByteArray()
}

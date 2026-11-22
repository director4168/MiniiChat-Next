/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

internal val boundedGetClient by lazy {
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 7000
            socketTimeoutMillis = 10000
            requestTimeoutMillis = 15000
        }
    }
}

internal suspend fun HttpResponse.readTextBounded(
    maxBytes: Int,
    charset: Charset = Charsets.UTF_8,
    allowTruncation: Boolean = false,
): Pair<String, Boolean> {
    require(maxBytes in 1..(64 * 1024 * 1024))
    val channel = bodyAsChannel()
    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    try {
        while (true) {
            val remaining = maxBytes - output.size()
            val count = channel.readAvailable(bytes, 0, minOf(bytes.size, remaining + 1))
            if (count < 0) return output.toString(charset.name()) to false
            if (count > remaining && !allowTruncation) throw InputLimitExceeded()
            output.write(bytes, 0, minOf(count, remaining))
            if (count > remaining) return output.toString(charset.name()) to true
        }
    } finally {
        channel.cancel(null)
    }
}

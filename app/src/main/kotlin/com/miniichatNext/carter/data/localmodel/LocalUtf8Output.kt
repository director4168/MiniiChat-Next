/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

internal class LocalUtf8Output {
    private var tail = byteArrayOf()

    fun append(bytes: ByteArray): String {
        val joined = tail + bytes
        var start = joined.lastIndex
        while (start >= 0 && joined.lastIndex - start < 3 && (joined[start].toInt() and 0xc0) == 0x80) start--
        val lead = if (start >= 0) joined[start].toInt() and 0xff else 0
        val length = when (lead) {
            in 0xc2..0xdf -> 2
            in 0xe0..0xef -> 3
            in 0xf0..0xf4 -> 4
            else -> 0
        }
        val end = if (length > joined.size - start) start else joined.size
        tail = joined.copyOfRange(end, joined.size)
        return joined.decodeToString(endIndex = end)
    }

    fun finish(): String = tail.decodeToString().also { tail = byteArrayOf() }
}

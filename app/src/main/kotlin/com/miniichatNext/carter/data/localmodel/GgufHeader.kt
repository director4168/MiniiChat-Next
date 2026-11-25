/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object GgufHeader {
    fun isModelFileName(name: String): Boolean = name.endsWith(".gguf", ignoreCase = true)

    fun isSupported(header: ByteArray): Boolean {
        if (header.size != 24) return false
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return data.int == 0x46554747 && data.int in 2..3 && data.long in 1..100_000 && data.long in 1..100_000
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import android.net.Uri
import com.miniichatNext.carter.data.localmodel.ExternalGgufRequests
import com.miniichatNext.carter.data.localmodel.GgufHeader
import com.miniichatNext.carter.data.localmodel.LocalModelParameters
import com.miniichatNext.carter.data.localmodel.LocalUtf8Output
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelsTests {
    private fun header(version: Int = 3, tensors: Long = 10, keys: Long = 20): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46554747).putInt(version).putLong(tensors).putLong(keys).array()

    @Test fun sameGgufCanBeOfferedAgainAfterCancel() {
        val requests = ExternalGgufRequests()
        val uri = Uri.parse("content://files/models/test.gguf")
        requests.offer(uri)
        val first = checkNotNull(requests.current.value)
        assertTrue(requests.consume(first))
        assertEquals(null, requests.current.value)
        requests.offer(uri)
        val second = checkNotNull(requests.current.value)
        assertFalse(first === second)
        assertEquals(uri, second.uri)
    }

    @Test fun staleGgufDialogCannotConsumeANewerRequest() {
        val requests = ExternalGgufRequests()
        val uri = Uri.parse("content://files/models/test.gguf")
        requests.offer(uri)
        val first = checkNotNull(requests.current.value)
        requests.offer(uri)
        val second = checkNotNull(requests.current.value)
        assertFalse(requests.consume(first))
        assertEquals(second, requests.current.value)
        assertTrue(requests.consume(second))
    }

    @Test fun onlyListsGgufFileNamesRegardlessOfCase() {
        assertTrue(GgufHeader.isModelFileName("Qwen.3.Q4.GGUF"))
        assertFalse(GgufHeader.isModelFileName("model.db"))
        assertFalse(GgufHeader.isModelFileName("model.gguf.db"))
        assertFalse(GgufHeader.isModelFileName("modelgguf"))
    }

    @Test fun checksMagicVersionAndHeaderLength() {
        assertTrue(GgufHeader.isSupported(header()))
        assertTrue(GgufHeader.isSupported(header(version = 2)))
        assertFalse(GgufHeader.isSupported(header(version = 1)))
        assertFalse(GgufHeader.isSupported(header().copyOf(23)))
        assertFalse(GgufHeader.isSupported(header().also { it[0] = 0 }))
    }

    @Test fun rejectsUnreasonableRecordCounts() {
        assertFalse(GgufHeader.isSupported(header(tensors = 0)))
        assertFalse(GgufHeader.isSupported(header(tensors = -1)))
        assertFalse(GgufHeader.isSupported(header(keys = 100_001)))
    }

    @Test fun validatesSamplingAndContextLimits() {
        val params = LocalModelParameters()
        assertTrue(params.isValid())
        assertTrue(params.copy(temperature = 0f, topP = 1f, topK = 0).isValid())
        assertFalse(params.copy(temperature = Float.NaN).isValid())
        assertFalse(params.copy(topP = 0f).isValid())
        assertFalse(params.copy(repeatPenalty = Float.POSITIVE_INFINITY).isValid())
        assertFalse(params.copy(contextSize = 256, maxTokens = 129).isValid())
        assertFalse(params.copy(threads = 0).isValid())
        assertFalse(params.copy(seed = -2).isValid())
    }

    @Test fun preservesSplitUtf8AndEmoji() {
        val value = "中文🌱abc"
        val bytes = value.encodeToByteArray()
        val decoder = LocalUtf8Output()
        assertEquals(value, bytes.joinToString("") { decoder.append(byteArrayOf(it)) } + decoder.finish())
    }

    @Test fun flushesOnlyIncompleteTail() {
        val decoder = LocalUtf8Output()
        assertEquals("A", decoder.append("A中".encodeToByteArray().copyOf(3)))
        assertEquals("\uFFFD", decoder.finish())
        assertEquals("", decoder.finish())
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.api.StreamDelta
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.model.recordUsage
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsageRecorderTests {
    @Test fun repeatedSnapshotsRecordOneRequestAndCachedTokens() = runBlocking {
        val recorded=mutableListOf<TokenUsage>()
        flow {
            emit(StreamDelta(usage=TokenUsage(100,5,105,cachedTokens=40)))
            emit(StreamDelta(usage=TokenUsage(100,5,105,cachedTokens=40)))
            emit(StreamDelta(usage=TokenUsage(0,10,10)))
        }.recordUsage { recorded += it }.collect { }
        assertEquals(1,recorded.size)
        assertEquals(100,recorded.single().promptTokens)
        assertEquals(10,recorded.single().completionTokens)
        assertEquals(40,recorded.single().cachedTokens)
        assertEquals(110,recorded.single().totalTokens)
    }

    @Test fun cancellationStillRecordsAlreadyReceivedUsageOnce() = runBlocking {
        val recorded=mutableListOf<TokenUsage>()
        val received=kotlinx.coroutines.CompletableDeferred<Unit>()
        val job=launch {
            flow { emit(StreamDelta(usage=TokenUsage(20,3,23))); received.complete(Unit); delay(5000) }
                .recordUsage { recorded += it }.collect { }
        }
        received.await(); job.cancelAndJoin()
        assertEquals(1,recorded.size)
        assertEquals(23,recorded.single().totalTokens)
    }

    @Test fun absentUsageDoesNotCreateARecord() = runBlocking {
        val recorded=mutableListOf<TokenUsage>()
        flow { emit(StreamDelta(text="answer")) }.recordUsage { recorded += it }.collect { }
        assertTrue(recorded.isEmpty())
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.TokenUsage
import com.miniichatNext.carter.data.model.merge
import com.miniichatNext.carter.data.model.sum
import com.miniichatNext.carter.data.model.withStepStats
import com.miniichatNext.carter.ui.chat.nerdStats
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test

class UsageMetricsTests {
    @Test fun snapshotsMergeWithoutDuplicatingInputOrCache() {
        val first = (null as TokenUsage?).merge(TokenUsage(100, 0, 100, cachedTokens = 40))
        val last = first.merge(TokenUsage(0, 20, 20))
        assertEquals(100, last.promptTokens)
        assertEquals(20, last.completionTokens)
        assertEquals(120, last.totalTokens)
        assertEquals(40, last.cachedTokens)
        assertEquals(last, last.merge(TokenUsage(100, 20, 120)))
    }

    @Test fun upstreamStepStatsKeepLastUsageSeparateFromTotal() {
        val base = Message("m", "assistant", "", usage = TokenUsage(100, 20, 120, cachedTokens = 40),
            generationTimeMs = 1000L, lastUsage = TokenUsage(100, 20, 120, cachedTokens = 40), stepCount = 1)
        val completed = base.copy(usage = TokenUsage(150, 30, 180, cachedTokens = 80), finishedAt = 9000L)
            .withStepStats(base, 2000.milliseconds)
        val stats = completed.nerdStats(completed.usage!!)
        assertEquals(250, stats.total.promptTokens)
        assertEquals(50, stats.total.completionTokens)
        assertEquals(120, stats.total.cachedTokens)
        assertEquals(300, stats.total.totalTokens)
        assertEquals(180, stats.contextTokens)
        assertEquals(80, stats.lastUsage.cachedTokens)
        assertEquals(3000L, stats.durationMillis)
        assertEquals(50_000f / 3000f, stats.tps!!, 0.001f)
        assertEquals(2, stats.steps)
    }

    @Test fun unfinishedMessageShowsLiveSpeed() {
        val msg = Message("m", "assistant", "live", usage = TokenUsage(100, 20, 120), generationTimeMs = 100L)
        assertEquals(100L, msg.nerdStats(msg.usage!!).durationMillis)
        assertEquals(200f, msg.nerdStats(msg.usage!!).tps!!, 0f)
    }

    @Test fun zeroUsageSnapshotKeepsPreviousContext() {
        val base = Message("m", "assistant", "", usage = TokenUsage(100, 20, 120), lastUsage = TokenUsage(100, 20, 120), stepCount = 1)
        val next = base.copy(usage = TokenUsage()).withStepStats(base, 100.milliseconds)
        assertEquals(120, next.contextTokens())
        assertEquals(120, next.lastUsage?.let { it.promptTokens + it.completionTokens })
    }

    @Test fun missingUsageRemainsNullAndPreviousStepContextRemains() {
        val base = Message("m", "assistant", "", usage = TokenUsage(100,20,120), lastUsage = TokenUsage(100,20,120), stepCount=1)
        val pending = base.copy(usage=null).withStepStats(base, 0.milliseconds)
        assertEquals(120,pending.contextTokens())
        assertEquals(base.usage,pending.usage)
        val empty=Message("e","assistant","").withStepStats(Message("e","assistant",""),500.milliseconds)
        assertNull(empty.usage)
    }

    @Test fun upstreamSumPreservesPerRequestTotalsAndCachedSubset() {
        val combined = TokenUsage(100,20,120,cachedTokens=80).sum(TokenUsage(200,30,230,cachedTokens=100))!!
        assertEquals(350,combined.totalTokens)
        assertEquals(180,combined.cachedTokens)
        assertEquals(300,combined.promptTokens)
    }

    @Test fun legacyFinishTimeFallbackMatchesUpstream() {
        val msg=Message("m","assistant","",createdAt=1000,finishedAt=3000,usage=TokenUsage(10,20,30))
        assertEquals(10f,msg.nerdStats(msg.usage!!).tps!!,0f)
    }
}

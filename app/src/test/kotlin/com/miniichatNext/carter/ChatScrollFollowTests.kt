/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.ui.chat.ChatScrollFollow
import com.miniichatNext.carter.ui.chat.settleChatBottomBeforeDraw
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollFollowTests {
    @Test fun initialTailFollowsOnlyDuringGenerationAndWhileIdle() {
        val follow = ChatScrollFollow()
        assertTrue(follow.shouldFollow(loading = true, scrolling = false))
        assertFalse(follow.shouldFollow(loading = false, scrolling = false))
        assertFalse(follow.shouldFollow(loading = true, scrolling = true))
    }

    @Test fun userScrollDisablesFollowingBeforeTheListConsumesMovement() {
        val follow = ChatScrollFollow().onUserScroll()
        assertFalse(follow.shouldFollow(true, false))
        assertFalse(follow.shouldFollow(true, true))
    }

    @Test fun liftingFingerInHistoryDoesNotRestartFollowing() {
        var follow = ChatScrollFollow().onUserScroll().onIdle(atBottom = false)
        repeat(1000) {
            follow = follow.onIdle(atBottom = false)
            assertFalse(follow.shouldFollow(true, false))
        }
    }

    @Test fun layoutReflowAloneNeverReenablesFollowing() {
        var follow = ChatScrollFollow().onUserScroll().onIdle(atBottom = false)
        // 折叠内容、键盘变高或消息收缩后刚好触底，也不算用户返回底部。
        follow = follow.onIdle(atBottom = true)
        assertFalse(follow.shouldFollow(true, false))
    }

    @Test fun scrollingBackToBottomResumesFollowingAfterFlingEnds() {
        var follow = ChatScrollFollow().onUserScroll().onIdle(atBottom = false)
        follow = follow.onUserScroll()
        assertFalse(follow.shouldFollow(true, true))
        follow = follow.onIdle(atBottom = true)
        assertTrue(follow.shouldFollow(true, false))
    }

    @Test fun generationRestartAndToolRoundDoNotChangeReadingMode() {
        val follow = ChatScrollFollow().onUserScroll().onIdle(atBottom = false)
        assertFalse(follow.shouldFollow(false, false))
        assertFalse(follow.shouldFollow(true, false))
    }

    @Test fun messageJumpsSuspendFollowingUntilTheirFinalPositionIsKnown() {
        val jumping = ChatScrollFollow().onJumpStarted()
        assertFalse(jumping.shouldFollow(true, false))
        assertFalse(jumping.shouldFollow(true, true))
        assertFalse(jumping.onJumpFinished(atBottom = false).shouldFollow(true, false))
        assertTrue(jumping.onJumpFinished(atBottom = true).shouldFollow(true, false))
    }

    @Test fun cancelledJumpDoesNotReenableFollowingAfterUserTakesOver() {
        val reading = ChatScrollFollow().onJumpStarted().onUserScroll()
        assertEquals(reading, reading.onJumpFinished(atBottom = true))
        assertFalse(reading.shouldFollow(true, false))
    }

    @Test fun staleJumpCompletionDoesNotOverrideANewReadingPosition() {
        val reading = ChatScrollFollow().onJumpStarted().onUserScroll().onIdle(atBottom = false)
        assertEquals(reading, reading.onJumpFinished(atBottom = true))
        assertFalse(reading.shouldFollow(true, false))
    }

    @Test fun sendingANewMessageExplicitlyReturnsToLatest() {
        val reading = ChatScrollFollow().onUserScroll().onIdle(atBottom = false)
        assertTrue(reading.followLatest().shouldFollow(true, false))
    }
    @Test fun frameIsPresentedOnlyAfterBottomRemeasureCompletes() = runBlocking {
        var pending = true
        val events = mutableListOf<String>()
        val ready = settleChatBottomBeforeDraw({ true }, { pending }) {
            events.add("remeasure")
            pending = false
        }
        if (ready) events.add("draw")
        assertEquals(listOf("remeasure", "draw"), events)
    }

    @Test fun frameWithUnsettledTailIsNotPresented() = runBlocking {
        assertFalse(settleChatBottomBeforeDraw({ true }, { true }) { })
    }

    @Test fun readingHistoryNeverRequestsBottomEvenBeforeDraw() = runBlocking {
        val follow = ChatScrollFollow().onUserScroll().onIdle(false)
        var calls = 0
        val ready = settleChatBottomBeforeDraw(
            { follow.shouldFollow(true, false) }, { true }, { calls++ },
        )
        assertTrue(ready)
        assertEquals(0, calls)
    }

    @Test fun pendingScrollDoesNotPresentIntermediatePosition() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var pending = true
        val frame = async(start = CoroutineStart.UNDISPATCHED) {
            settleChatBottomBeforeDraw({ true }, { pending }) {
                release.await()
                pending = false
            }
        }
        assertFalse(frame.isCompleted)
        release.complete(Unit)
        assertTrue(frame.await())
    }

    @Test fun repeatedTokenGrowthHasNoDrawBeforeBottomIsSettled() = runBlocking {
        var corrections = 0
        repeat(1000) {
            var pending = true
            val ready = settleChatBottomBeforeDraw({ true }, { pending }) {
                corrections++
                pending = false
            }
            assertTrue(ready)
            assertFalse(pending)
        }
        assertEquals(1000, corrections)
    }

    @Test fun alreadyAtBottomDoesNotStartAnotherScroll() = runBlocking {
        var calls = 0
        assertTrue(settleChatBottomBeforeDraw({ true }, { false }) { calls++ })
        assertEquals(0, calls)
    }

}

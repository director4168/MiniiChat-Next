/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.ui.chat.ChatListOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageJumpTests {
    private val viewport = 500

    @Test fun upFromLongDAtBottomGoesToDStartInsteadOfC() {
        val hidden = ChatListOrder.boundaryHidden(1, 0, 1200, viewport)
        assertTrue(hidden)
        assertEquals(0, ChatListOrder.jumpTarget(1, 0, 4, hidden))
        assertEquals(700, ChatListOrder.jumpOffset(1, 1200, viewport))
    }

    @Test fun upFromMiddleOfDStaysInDAndAlignsItsStart() {
        val hidden = ChatListOrder.boundaryHidden(1, -350, 1200, viewport)
        assertEquals(0, ChatListOrder.jumpTarget(1, 0, 4, hidden))
        val targetOffset = ChatListOrder.jumpOffset(1, 1200, viewport)
        assertEquals(700, targetOffset)
        assertEquals(0, viewport + targetOffset - 1200)
    }

    @Test fun upFromDStartCanContinueToC() {
        val hidden = ChatListOrder.boundaryHidden(1, -700, 1200, viewport)
        assertFalse(hidden)
        assertEquals(1, ChatListOrder.jumpTarget(1, 0, 4, hidden))
    }

    @Test fun shortCStartAlignsWithViewportTopInsteadOfBottom() {
        assertEquals(-340, ChatListOrder.jumpOffset(1, 160, viewport))
        assertEquals(0, viewport + ChatListOrder.jumpOffset(1, 160, viewport) - 160)
    }

    @Test fun upFromCStartContinuesToB() {
        assertFalse(ChatListOrder.boundaryHidden(1, 340, 160, viewport))
        assertEquals(2, ChatListOrder.jumpTarget(1, 1, 4))
    }

    @Test fun downAlwaysTargetsNextMessageEvenWhenCurrentEndIsHidden() {
        assertEquals(1, ChatListOrder.jumpTarget(2, 2, 4, true))
        assertEquals(0, ChatListOrder.jumpTarget(2, 1, 4, true))
        assertEquals(0, ChatListOrder.jumpTarget(2, 0, 4, true))
        assertEquals(700, ChatListOrder.jumpOffset(2, 1200, viewport))
        assertEquals(-340, ChatListOrder.jumpOffset(2, 160, viewport))
    }

    @Test fun shortAndLongMessagesUseTheSameDirectionRules() {
        for (size in listOf(80, 500, 1200)) {
            val offset = ChatListOrder.jumpOffset(1, size, viewport)
            assertEquals(0, viewport + offset - size)
            assertEquals(size - viewport, ChatListOrder.jumpOffset(2, size, viewport))
        }
    }

    @Test fun singlePixelTolerancePreventsRepeatedBoundaryJumps() {
        assertFalse(ChatListOrder.boundaryHidden(1, 1, 500, viewport))
        assertTrue(ChatListOrder.boundaryHidden(1, 2, 500, viewport))
        assertFalse(ChatListOrder.boundaryHidden(2, -1, 500, viewport))
        assertFalse(ChatListOrder.boundaryHidden(2, -2, 500, viewport))
    }

    @Test fun extremeMessageSizeDoesNotOverflowBoundaryTest() {
        assertTrue(ChatListOrder.boundaryHidden(1, Int.MAX_VALUE, 100, viewport))
        assertFalse(ChatListOrder.boundaryHidden(1, Int.MIN_VALUE, 100, viewport))
    }

    @Test fun firstAndLastButtonsRemainClampedToConversationEnds() {
        assertEquals(3, ChatListOrder.jumpTarget(0, 1, 4, true))
        assertEquals(0, ChatListOrder.jumpTarget(3, 1, 4, true))
        assertEquals(null, ChatListOrder.jumpTarget(1, 0, 0, true))
    }
    @Test fun visualTopCountsFromContentBottomInReverseLayout() {
        assertEquals(0, ChatListOrder.visualTop(itemOffset = 0, itemSize = 500, contentBottom = 500, reverseLayout = true))
        assertEquals(200, ChatListOrder.visualTop(itemOffset = 300, itemSize = 0, contentBottom = 500, reverseLayout = true))
        assertEquals(120, ChatListOrder.visualTop(itemOffset = 120, itemSize = 0, contentBottom = 500, reverseLayout = false))
    }

    @Test fun topOwnerPrefersTheMessageSpanningTheTopEdge() {
        val tops = listOf(2 to -900, 1 to 120, 0 to 400)
        assertEquals(2, ChatListOrder.topOwnerIndex(tops, listOf(1000, 160, 100)))
    }

    @Test fun topOwnerFallsBackToTheNearestMessageWhenNothingSpansTheEdge() {
        // 顶边正好落在两条消息的间距里：上面那条已完全越过顶边
        assertEquals(1, ChatListOrder.topOwnerIndex(listOf(2 to -520, 1 to 10), listOf(500, 300)))
        assertEquals(null, ChatListOrder.topOwnerIndex(emptyList(), emptyList()))
    }

}

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
        assertEquals(700f, ChatListOrder.jumpDistance(1, 0, 1200, viewport))
    }

    @Test fun upFromMiddleOfDStaysInDAndAlignsItsStart() {
        val hidden = ChatListOrder.boundaryHidden(1, -350, 1200, viewport)
        assertEquals(0, ChatListOrder.jumpTarget(1, 0, 4, hidden))
        val distance = ChatListOrder.jumpDistance(1, -350, 1200, viewport)
        assertEquals(350f, distance)
        val updatedOffset = -350 - distance.toInt()
        assertEquals(0, viewport - updatedOffset - 1200)
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

    @Test fun downFromDStartGoesOnlyToDEnd() {
        val hidden = ChatListOrder.boundaryHidden(2, -700, 1200, viewport)
        assertTrue(hidden)
        assertEquals(0, ChatListOrder.jumpTarget(2, 0, 4, hidden))
        val distance = ChatListOrder.jumpDistance(2, -700, 1200, viewport)
        assertEquals(-700f, distance)
        assertEquals(0f, -700f - distance)
    }

    @Test fun downFromMiddleOfDDoesNotJumpBackToItsStart() {
        assertEquals(-350f, ChatListOrder.jumpDistance(2, -350, 1200, viewport))
        assertEquals(0, ChatListOrder.jumpOffset(2, 1200, viewport))
    }

    @Test fun downAtDEndIsANoOp() {
        assertFalse(ChatListOrder.boundaryHidden(2, 0, 1200, viewport))
        assertEquals(0, ChatListOrder.jumpTarget(2, 0, 4))
        assertEquals(0f, ChatListOrder.jumpDistance(2, 0, 1200, viewport))
    }

    @Test fun downFromCEndTargetsDEnd() {
        assertFalse(ChatListOrder.boundaryHidden(2, 0, 160, viewport))
        assertEquals(0, ChatListOrder.jumpTarget(2, 1, 4))
        assertEquals(0, ChatListOrder.jumpOffset(2, 1200, viewport))
    }

    @Test fun shortAndLongMessagesUseTheSameDirectionRules() {
        for (size in listOf(80, 500, 1200)) {
            val offset = ChatListOrder.jumpOffset(1, size, viewport)
            assertEquals(0, viewport + offset - size)
            assertEquals(0, ChatListOrder.jumpOffset(2, size, viewport))
        }
    }

    @Test fun singlePixelTolerancePreventsRepeatedBoundaryJumps() {
        assertFalse(ChatListOrder.boundaryHidden(1, 1, 500, viewport))
        assertTrue(ChatListOrder.boundaryHidden(1, 2, 500, viewport))
        assertFalse(ChatListOrder.boundaryHidden(2, -1, 500, viewport))
        assertTrue(ChatListOrder.boundaryHidden(2, -2, 500, viewport))
    }

    @Test fun revealPreviousMessageOnlyMovesItIntoMeasurement() {
        assertEquals(21f, ChatListOrder.revealDistance(1, -700, 1200, viewport, 20))
    }

    @Test fun revealNextMessageKeepsDownwardDirection() {
        assertEquals(-21f, ChatListOrder.revealDistance(2, 0, 160, viewport, 20))
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
}

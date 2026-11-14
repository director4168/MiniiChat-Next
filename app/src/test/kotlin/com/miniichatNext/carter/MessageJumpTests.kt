/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.ui.chat.ChatListOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageJumpTests {
    @Test fun listAndMessageIndicesUseTheSameChronologicalOrder() {
        assertEquals(listOf(0, 1, 2, 3), (0..3).map { ChatListOrder.messageIndex(it, 4) })
        assertEquals(null, ChatListOrder.messageIndex(4, 4))
        assertEquals(null, ChatListOrder.messageIndex(-1, 4))
    }

    @Test fun upFromMiddleOfLongMessageFirstVisitsItsStart() {
        assertEquals(2, ChatListOrder.jumpTarget(1, 2, 4, boundaryHidden = true))
        assertEquals(1, ChatListOrder.jumpTarget(1, 2, 4, boundaryHidden = false))
    }

    @Test fun downTargetsTheNextMessageWithoutVisitingCurrentEnd() {
        assertEquals(2, ChatListOrder.jumpTarget(2, 1, 4, boundaryHidden = true))
        assertEquals(2, ChatListOrder.jumpTarget(2, 1, 4, boundaryHidden = false))
    }

    @Test fun newestMessageHasNoNextMessage() {
        assertEquals(3, ChatListOrder.jumpTarget(2, 3, 4))
    }

    @Test fun buttonsKeepVisualDirectionAtBothEnds() {
        assertEquals(0, ChatListOrder.jumpTarget(0, 2, 4))
        assertEquals(3, ChatListOrder.jumpTarget(3, 1, 4))
        assertEquals(0, ChatListOrder.jumpTarget(1, 0, 4))
        assertEquals(3, ChatListOrder.jumpTarget(2, 3, 4))
    }

    @Test fun emptyAndInvalidActionsHaveNoTarget() {
        (0..3).forEach { action -> assertEquals(null, ChatListOrder.jumpTarget(action, 0, 0)) }
        assertEquals(null, ChatListOrder.jumpTarget(-1, 0, 4))
        assertEquals(null, ChatListOrder.jumpTarget(4, 0, 4))
    }
}

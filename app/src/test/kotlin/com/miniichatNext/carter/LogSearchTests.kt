/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.debug.ui.findLogSearchRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSearchTests {
    @Test fun highlightsOnlyMatchedSubstring() {
        assertEquals(listOf(7..11), findLogSearchRanges("prefix error suffix", "error"))
    }

    @Test fun findsEveryOccurrenceIgnoringCase() {
        assertEquals(listOf(0..3, 5..8, 10..13), findLogSearchRanges("WARN warn WaRn", "warn"))
    }

    @Test fun matchesAtBothLineEdges() {
        assertEquals(listOf(0..2, 8..10), findLogSearchRanges("err ... err", "err"))
    }

    @Test fun supportsWholeLineMatch() {
        assertEquals(listOf(0..8), findLogSearchRanges("whole log", "WHOLE LOG"))
    }

    @Test fun preservesLiteralSearchForRegexCharacters() {
        assertEquals(listOf(0..5, 7..12), findLogSearchRanges("[id].* [ID].*", "[id].*"))
    }

    @Test fun skipsBlankQueriesAndEmptyLines() {
        listOf("", " ", "\t").forEach { query ->
            assertTrue(findLogSearchRanges("log entry", query).isEmpty())
        }
        assertTrue(findLogSearchRanges("", "entry").isEmpty())
    }

    @Test fun returnsNoRangesForMissingOrLongerQueries() {
        assertTrue(findLogSearchRanges("entry", "other").isEmpty())
        assertTrue(findLogSearchRanges("entry", "long entry").isEmpty())
    }

    @Test fun usesNonOverlappingMatches() {
        assertEquals(listOf(0..1, 2..3), findLogSearchRanges("aaaaa", "aa"))
        assertEquals(listOf(1..3), findLogSearchRanges("banana", "ana"))
    }

    @Test fun preservesUtf16OffsetsForAnnotatedString() {
        assertEquals(listOf(3..6, 11..14), findLogSearchRanges("🙂 WARN 警告 warn", "warn"))
        assertEquals(listOf(3..4, 6..7), findLogSearchRanges("日志：警告，警告", "警告"))
    }

    @Test fun changingQueryReplacesOldMatchRanges() {
        val text = "error and warning"
        assertEquals(listOf(0..4), findLogSearchRanges(text, "error"))
        assertEquals(listOf(10..16), findLogSearchRanges(text, "warning"))
        assertTrue(findLogSearchRanges(text, "").isEmpty())
    }
}

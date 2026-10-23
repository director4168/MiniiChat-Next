package com.miniichatNext.carter

import com.miniichatNext.carter.ui.markdown.HighlightPalette
import com.miniichatNext.carter.ui.markdown.highlightCode
import com.miniichatNext.carter.ui.markdown.MarkdownBlock
import com.miniichatNext.carter.ui.markdown.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongCodeHighlightTests {
    @Test
    fun fencedLongCodeRetainsLanguageAndColorsThroughMarkdownParser() {
        val code = (1..600).joinToString("\n") { "val row$it: String = \"value $it\"" }
        val document = parseMarkdown("```kotlin\n" + code + "\n```")
        val block = document.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("kotlin", block.language)
        assertEquals(code, block.code)
        assertTrue(block.closed)
        val highlighted = highlightCode(block.code, block.language.orEmpty(), HighlightPalette.Dark)
        val last = code.indexOf("val row600:")
        assertTrue(highlighted.spanStyles.any {
            it.start == last && it.end == last + 3 && it.item.color == HighlightPalette.Dark.keyword
        })
    }

    @Test
    fun highlightingContinuesPast160LinesAnd20KCharacters() {
        val code = (1..600).joinToString("\n") { line ->
            "val line$line: String = \"long highlight regression row $line\" // preserved comment"
        }
        assertTrue(code.length > 20_000)
        val highlighted = highlightCode(code, "kotlin", HighlightPalette.Dark)
        assertEquals(code, highlighted.text)
        val lateLine = code.indexOf("val line450:")
        assertTrue(lateLine > 0)
        assertTrue(highlighted.spanStyles.any {
            it.start == lateLine && it.end == lateLine + 3 && it.item.color == HighlightPalette.Dark.keyword
        })
        val lastString = code.indexOf("\"long highlight regression row 600\"")
        assertTrue(highlighted.spanStyles.any {
            it.start == lastString && it.item.color == HighlightPalette.Dark.string
        })
    }

    @Test
    fun multiLineCommentCrosses160LineBoundaryWithoutLosingState() {
        val prefix = (1..159).joinToString("\n") { "val row$it = $it" } + "\n"
        val comment = "/* line 160 starts comment\nval notAKeyword = 0\ncomment ends here */"
        val code = prefix + comment + "\nval afterComment = 162"
        val highlighted = highlightCode(code, "kotlin", HighlightPalette.Dark)
        assertEquals(code, highlighted.text)
        assertTrue(highlighted.spanStyles.any {
            it.start == prefix.length && it.end == prefix.length + comment.length &&
                it.item.color == HighlightPalette.Dark.comment
        })
        val after = code.indexOf("val afterComment")
        assertTrue(highlighted.spanStyles.any {
            it.start == after && it.item.color == HighlightPalette.Dark.keyword
        })
    }
}

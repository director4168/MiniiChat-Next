package com.miniichatNext.carter.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.miniichatNext.carter.ui.markdown.lang.ALL_LANGUAGES
import com.miniichatNext.carter.ui.markdown.lang.LangRules

private class Span(val start: Int, val end: Int, val style: SpanStyle)

internal fun highlightCode(code: String, lang: String, palette: HighlightPalette): AnnotatedString {
    val rules = ALL_LANGUAGES[lang.lowercase()] ?: return AnnotatedString(code)
    val spans = scan(code, rules, palette)
    if (spans.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        var cursor = 0
        spans.forEach { span ->
            if (span.start > cursor) append(code.substring(cursor, span.start))
            if (span.end > span.start) {
                pushStyle(span.style)
                append(code.substring(span.start, span.end))
                pop()
            }
            cursor = span.end
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}

private fun scan(code: String, rules: LangRules, palette: HighlightPalette): List<Span> {
    val spans = mutableListOf<Span>()
    val len = code.length
    var i = 0
    while (i < len) {
        val ch = code[i]

        val blockOpen = rules.blockCommentOpen
        if (blockOpen != null && code.startsWith(blockOpen, i)) {
            val close = rules.blockCommentClose ?: blockOpen
            val end = code.indexOf(close, i + blockOpen.length)
            val finish = if (end < 0) len else end + close.length
            spans.add(Span(i, finish, SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)))
            i = finish
            continue
        }

        val lineComment = rules.lineComment
        if (lineComment != null && code.startsWith(lineComment, i)) {
            val nl = code.indexOf('\n', i)
            val finish = if (nl < 0) len else nl
            spans.add(Span(i, finish, SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)))
            i = finish
            continue
        }

        if (ch in rules.stringDelims) {
            val finish = scanString(code, i, ch)
            spans.add(Span(i, finish, SpanStyle(color = palette.string)))
            i = finish
            continue
        }

        if (ch.isDigit()) {
            var j = i + 1
            while (j < len && (code[j].isDigit() || code[j] in ".xXabcdefABCDEF_")) j++
            spans.add(Span(i, j, SpanStyle(color = palette.number)))
            i = j
            continue
        }

        if (ch.isLetter() || ch == '_' || ch == '$') {
            var j = i + 1
            while (j < len && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val word = code.substring(i, j)
            val lower = word.lowercase()
            val style = when {
                word in rules.keywords || lower in rules.keywordsLower -> SpanStyle(color = palette.keyword)
                word in rules.types || lower in rules.typesLower -> SpanStyle(color = palette.type)
                word in rules.functions || lower in rules.functionsLower -> SpanStyle(color = palette.function)
                word.first().isUpperCase() -> SpanStyle(color = palette.type)
                else -> null
            }
            if (style != null) spans.add(Span(i, j, style))
            i = j
            continue
        }

        i++
    }
    return spans
}

private fun scanString(code: String, start: Int, delim: Char): Int {
    var i = start + 1
    val len = code.length
    while (i < len) {
        val c = code[i]
        if (c == '\\' && i + 1 < len) {
            i += 2
            continue
        }
        if (c == delim) return i + 1
        if (c == '\n' && delim != '`') return i
        i++
    }
    return len
}

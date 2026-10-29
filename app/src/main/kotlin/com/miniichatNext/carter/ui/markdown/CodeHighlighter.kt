package com.miniichatNext.carter.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import android.util.LruCache
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.miniichatNext.carter.ui.markdown.lang.ALL_LANGUAGES
import com.miniichatNext.carter.ui.markdown.lang.LangRules

private class Span(val start: Int, val end: Int, val style: SpanStyle)

private data class HighlightKey(val code: String, val lang: String, val palette: HighlightPalette)
private val highlightCache = object : LruCache<HighlightKey, AnnotatedString>(24) {}

/** #RGB/ #RRGGBB/ #RRGGBBAA（matchAt从#处开始匹配） */
private val HEX_COLOR_REGEX = Regex("#[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}(?![0-9a-fA-F])")

/** "RRGGBB"/"RGB"/"RRGGBBAA"→ARGB Int；失败返回null */
private fun hexToArgb(hex: String): Int? {
    return runCatching {
        val value = hex.toLong(16)
        when (hex.length) {
            3 -> {
                val r = ((value shr 8) and 0xF).toInt() * 0x11
                val g = ((value shr 4) and 0xF).toInt() * 0x11
                val b = (value and 0xF).toInt() * 0x11
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> {
                val v = value.toInt()
                (0xFF shl 24) or ((v shr 16 and 0xFF) shl 16) or ((v shr 8 and 0xFF) shl 8) or (v and 0xFF)
            }
            8 -> value.toInt()   // Long低32位直通ARGB（含alpha），超出Int范围的补码正好是正确位型
            else -> null
        }
    }.getOrNull()
}

/** 在hex颜色文字背后铺一层保证可读的对比底色：亮字给深底、暗字给浅底 */
private fun contrastingBg(argb: Int): androidx.compose.ui.graphics.Color {
    val c = androidx.compose.ui.graphics.Color(argb)
    return if (c.luminance() > 0.5f) androidx.compose.ui.graphics.Color(0xFF1A1A1A)
    else androidx.compose.ui.graphics.Color(0xFFF2F3F5)
}

internal fun highlightCode(code: String, lang: String, palette: HighlightPalette): AnnotatedString {
    val key = HighlightKey(code, lang.lowercase(), palette)
    synchronized(highlightCache) { highlightCache.get(key)?.let { return it } }
    val rules = ALL_LANGUAGES[lang.lowercase()] ?: return AnnotatedString(code)
    val spans = scan(code, rules, palette)
    if (spans.isEmpty()) return AnnotatedString(code).also { synchronized(highlightCache) { highlightCache.put(key, it) } }
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
    }.also { synchronized(highlightCache) { highlightCache.put(key, it) } }
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
            // 后面紧跟冒号的字符串按键上色，其余按字符串
            var k = finish
            while (k < len && code[k] == ' ') k++
            val isKey = k < len && code[k] == ':'
            spans.add(Span(i, finish, SpanStyle(color = if (isKey) palette.property else palette.string)))
            i = finish
            continue
        }

        // 十六进制颜色#RGB/ #RRGGBB/ #RRGGBBAA：渲染成颜色本身+自动对比背景色
        if (ch == '#' && rules.allowHexColors) {
            val m = HEX_COLOR_REGEX.matchAt(code, i)
            if (m != null) {
                val end = m.range.last + 1
                val hexText = code.substring(i + 1, end)
                val argb = hexToArgb(hexText)
                if (argb != null) {
                    val bg = contrastingBg(argb)
                    spans.add(Span(i, end, SpanStyle(
                        color = androidx.compose.ui.graphics.Color(argb),
                        background = bg,
                    )))
                    i = end
                    continue
                }
            }
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

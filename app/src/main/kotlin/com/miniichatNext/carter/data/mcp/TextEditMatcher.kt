package com.miniichatNext.carter.data.mcp

import java.io.File

internal data class TextEditMatch(val start: Int, val endExclusive: Int, val replacement: String)
internal data class TextEditPlan(val matches: List<TextEditMatch> = emptyList(), val error: String? = null)


internal fun planTextEdit(content: String, oldText: String, newText: String, replaceAll: Boolean): TextEditPlan {
    if (oldText.isEmpty()) return TextEditPlan(error = "old_text must not be empty")
    val newline = when {
        content.contains("\r\n") -> "\r\n"
        content.contains('\n') -> "\n"
        content.contains('\r') -> "\r"
        else -> "\n"
    }
    val replacementText = newText.replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline)
    val exact = mutableListOf<TextEditMatch>()
    var index = content.indexOf(oldText)
    while (index >= 0) {
        exact += TextEditMatch(index, index + oldText.length, replacementText)
        index = content.indexOf(oldText, index + oldText.length)
    }
    if (exact.isNotEmpty()) return selectMatches(exact, replaceAll)

    val oldLines = splitLines(oldText).let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    if (oldLines.none { it.isNotBlank() }) return TextEditPlan(error = notFound())
    val currentLines = lineSpans(content)
    val matches = mutableListOf<TextEditMatch>()
    for (start in 0..currentLines.size - oldLines.size) {
        val window = currentLines.subList(start, start + oldLines.size)
        if (window.map { it.text.trim() } != oldLines.map { it.trim() }) continue
        val base = oldLines.indexOfFirst { it.isNotBlank() }
        val oldIndent = oldLines[base].takeWhile { it == ' ' || it == '\t' }
        val newIndent = window[base].text.takeWhile { it == ' ' || it == '\t' }
        val shifted = splitLines(replacementText).joinToString(newline) { line ->
            when {
                line.isBlank() -> line
                line.startsWith(oldIndent) -> newIndent + line.removePrefix(oldIndent)
                else -> newIndent + line
            }
        }
        val consumeTrailingNewline = oldText.endsWith("\n") || oldText.endsWith("\r")
        val matchedEnd = if (consumeTrailingNewline) {
            val end = window.last().endExclusive
            when {
                content.startsWith("\r\n", end) -> end + 2
                end < content.length && (content[end] == '\r' || content[end] == '\n') -> end + 1
                else -> end
            }
        } else window.last().endExclusive
        matches += TextEditMatch(window.first().start, matchedEnd, shifted)
    }
    if (matches.isEmpty()) return TextEditPlan(error = notFound())
    return selectMatches(matches, replaceAll)
}

private data class LineSpan(val text: String, val start: Int, val endExclusive: Int)

private fun lineSpans(value: String): List<LineSpan> {
    val lines = mutableListOf<LineSpan>()
    var start = 0
    var i = 0
    while (i < value.length) {
        if (value[i] == '\n' || value[i] == '\r') {
            lines += LineSpan(value.substring(start, i), start, i)
            if (value[i] == '\r' && i + 1 < value.length && value[i + 1] == '\n') i++
            i++
            start = i
        } else i++
    }
    if (start <= value.length) lines += LineSpan(value.substring(start), start, value.length)
    return lines
}

private fun splitLines(value: String) = lineSpans(value).map { it.text }

private fun selectMatches(matches: List<TextEditMatch>, replaceAll: Boolean): TextEditPlan {
    val sorted = matches.sortedBy { it.start }
    if (!replaceAll && sorted.size > 1) return TextEditPlan(error = "old_text matches ${sorted.size} locations; add surrounding context or set replace_all=true")
    if (!replaceAll) return TextEditPlan(matches = listOf(sorted.first()))
    val nonOverlapping = mutableListOf<TextEditMatch>()
    var end = -1
    sorted.forEach { match -> if (match.start >= end) { nonOverlapping += match; end = match.endExclusive } }
    return TextEditPlan(matches = nonOverlapping)
}

private fun notFound() = "old_text not found with exact or whitespace-tolerant line matching; re-read the file and copy old_text exactly"

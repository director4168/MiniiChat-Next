package com.miniichatNext.carter.ui.markdown

internal sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Paragraph(val text: String) : Block()
    data class BulletItem(val text: String) : Block()
    data class NumberedItem(val index: Int, val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class CodeBlock(val lang: String, val code: String) : Block()
    object Hr : Block()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Block()
}

internal fun parseBlocks(text: String): List<Block> {
    val result = mutableListOf<Block>()
    val lines = text.split("\n")
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            result.add(Block.Paragraph(paragraph.toString().trim()))
        }
        paragraph.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.append(lines[i]).append('\n'); i++
            }
            result.add(Block.CodeBlock(lang, code.toString().trimEnd('\n')))
            if (i < lines.size) i++
            continue
        }

        if (trimmed.startsWith("|")) {
            val tableLines = mutableListOf<String>()
            var j = i
            while (j < lines.size && lines[j].trimStart().startsWith("|")) {
                tableLines.add(lines[j]); j++
            }
            if (tableLines.size >= 2 && tableLines.any { isTableSeparator(it) }) {
                flushParagraph()
                val headers = splitTableRow(tableLines.first())
                val sepIdx = tableLines.indexOfFirst { isTableSeparator(it) }
                val rows = tableLines.withIndex()
                    .filter { (idx, _) -> idx != 0 && idx != sepIdx }
                    .map { (_, l) -> splitTableRow(l) }
                    .map { split ->
                        if (split.size >= headers.size) split.take(headers.size)
                        else split + List(headers.size - split.size) { "" }
                    }
                result.add(Block.Table(headers, rows))
                i = j
                continue
            }
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph(); result.add(Block.Hr); i++; continue
        }
        val headingMatch = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            result.add(Block.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++; continue
        }
        if (trimmed.startsWith("> ") || trimmed == ">") {
            flushParagraph()
            result.add(Block.Quote(trimmed.removePrefix(">").trimStart()))
            i++; continue
        }
        val bulletMatch = Regex("^[-*+]\\s+(.+)$").matchEntire(trimmed)
        if (bulletMatch != null) {
            flushParagraph(); result.add(Block.BulletItem(bulletMatch.groupValues[1])); i++; continue
        }
        val numberedMatch = Regex("^(\\d+)[.)]\\s+(.+)$").matchEntire(trimmed)
        if (numberedMatch != null) {
            flushParagraph()
            result.add(Block.NumberedItem(numberedMatch.groupValues[1].toInt(), numberedMatch.groupValues[2]))
            i++; continue
        }
        if (line.isBlank()) { flushParagraph(); i++; continue }
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(line.trim())
        i++
    }
    flushParagraph()
    return result
}

private fun isTableSeparator(line: String): Boolean {
    val s = line.trim()
    // 至少要 "|x|" 才能取中间段：s = "|" 会同时startsWith/endsWith，substring(1, 0)直接崩
    if (s.length < 3 || !s.startsWith("|")) return false
    val inner = if (s.endsWith("|")) s.substring(1, s.length - 1) else s.substring(1)
    if (inner.isBlank()) return false
    return inner.split("|").all { it.trim().matches(Regex("^:?-{1,}:?$")) }
}

private fun splitTableRow(line: String): List<String> {
    val s = line.trim()
    val core = when {
        s.length >= 2 && s.startsWith("|") && s.endsWith("|") -> s.substring(1, s.length - 1)
        s.startsWith("|") -> s.substring(1)
        else -> s
    }
    return core.split("|").map { it.trim() }
}

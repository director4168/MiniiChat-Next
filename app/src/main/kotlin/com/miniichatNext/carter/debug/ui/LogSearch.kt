/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.debug.ui

internal fun findLogSearchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank() || query.length > text.length) return emptyList()
    return buildList {
        var start = 0
        while (start <= text.length - query.length) {
            val match = text.indexOf(query, startIndex = start, ignoreCase = true)
            if (match < 0) break
            val end = match + query.length
            add(match until end)
            start = end
        }
    }
}

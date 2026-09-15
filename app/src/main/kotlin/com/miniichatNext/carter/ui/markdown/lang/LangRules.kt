package com.miniichatNext.carter.ui.markdown.lang

internal data class LangRules(
    val keywords: Set<String>,
    val types: Set<String>,
    val functions: Set<String>,
    val lineComment: String?,
    val blockCommentOpen: String?,
    val blockCommentClose: String?,
    val stringDelims: List<Char>,
) {
    val keywordsLower: Set<String> = keywords.mapTo(HashSet()) { it.lowercase() }
    val typesLower: Set<String> = types.mapTo(HashSet()) { it.lowercase() }
    val functionsLower: Set<String> = functions.mapTo(HashSet()) { it.lowercase() }
}

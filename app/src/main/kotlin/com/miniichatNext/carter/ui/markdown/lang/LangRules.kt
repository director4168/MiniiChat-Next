/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown.lang

import java.util.Locale

internal data class LangRules(
    val keywords: Set<String>,
    val types: Set<String>,
    val functions: Set<String>,
    val lineComment: String?,
    val blockCommentOpen: String?,
    val blockCommentClose: String?,
    val stringDelims: List<Char>,
    /** 是否渲染#RRGGBB(AA)为颜色本身（hex颜色高亮） */
    val allowHexColors: Boolean = true,
) {
    val keywordsLower: Set<String> = keywords.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val typesLower: Set<String> = types.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val functionsLower: Set<String> = functions.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
}

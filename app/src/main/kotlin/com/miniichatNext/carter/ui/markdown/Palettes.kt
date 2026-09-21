package com.miniichatNext.carter.ui.markdown

import androidx.compose.ui.graphics.Color

internal data class InlinePalette(
    val codeBg: Color,
    val codeFg: Color,
    val langFg: Color,
    val lineNumberFg: Color,
    val link: Color,
    val quoteBar: Color,
    val quoteFg: Color,
    val divider: Color,
    val headerBg: Color,
    val headerFg: Color,
    val cellFg: Color,
    val outline: Color,
)

/**
 * 助手气泡是固定深色，所以md内容也固定用深底配色
 * 若跟着主题走，浅色主题下onSurface是深色，深字压深气泡=表格内容看不见；分割线也会太浅
 */
internal val BubblePalette = InlinePalette(
    codeBg = Color(0xFF23262E),
    codeFg = Color(0xFFECECEC),
    langFg = Color(0xFF9AA3B2),
    lineNumberFg = Color(0xFF6C7480),
    link = Color(0xFF8AB4F8),
    quoteBar = Color(0xFF5A6270),
    quoteFg = Color(0xFFB4BAC4),
    divider = Color(0xFF3A3F4B),
    headerBg = Color(0xFF2A2E38),
    headerFg = Color(0xFFECECEC),
    cellFg = Color(0xFFECECEC),
    outline = Color(0xFF444A57),
)

internal data class HighlightPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
    val property: Color,
) {
    companion object {
        val Light = HighlightPalette(
            keyword = Color(0xFFAF00DB),
            string = Color(0xFFA31515),
            number = Color(0xFF098658),
            comment = Color(0xFF008000),
            type = Color(0xFF267F99),
            function = Color(0xFF795E26),
            property = Color(0xFF0451A5),
        )
        val Dark = HighlightPalette(
            keyword = Color(0xFFC678DD),
            string = Color(0xFF98C379),
            number = Color(0xFFD19A66),
            comment = Color(0xFF7F848E),
            type = Color(0xFFE5C07B),
            function = Color(0xFF61AFEF),
            property = Color(0xFF9CDCFE),
        )
    }
}

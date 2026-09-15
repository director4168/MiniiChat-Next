package com.miniichatNext.carter.ui.markdown

import androidx.compose.ui.graphics.Color

internal data class InlinePalette(
    val codeBg: Color,
    val codeFg: Color,
    val langFg: Color,
    val link: Color,
    val quoteBar: Color,
    val quoteFg: Color,
    val divider: Color,
    val headerBg: Color,
    val headerFg: Color,
    val cellFg: Color,
    val outline: Color,
)

internal data class HighlightPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
) {
    companion object {
        val Light = HighlightPalette(
            keyword = Color(0xFFAF00DB),
            string = Color(0xFFA31515),
            number = Color(0xFF098658),
            comment = Color(0xFF008000),
            type = Color(0xFF267F99),
            function = Color(0xFF795E26),
        )
        val Dark = HighlightPalette(
            keyword = Color(0xFFC678DD),
            string = Color(0xFF98C379),
            number = Color(0xFFD19A66),
            comment = Color(0xFF7F848E),
            type = Color(0xFFE5C07B),
            function = Color(0xFF61AFEF),
        )
    }
}

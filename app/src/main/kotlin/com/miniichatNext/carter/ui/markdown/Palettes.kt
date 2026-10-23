package com.miniichatNext.carter.ui.markdown

import androidx.compose.ui.graphics.Color

internal data class InlinePalette(
    val codeBg: Color,
    val codeFg: Color,
    /** 行内`code`用更亮的底色，让单字符/单词的code也显眼 */
    val inlineCodeBg: Color,
    val inlineCodeFg: Color,
    val langFg: Color,
    val lineNumberFg: Color,
    val link: Color,
    val quoteBar: Color,
    val quoteFg: Color,
    val outline: Color,
    /** 卡片头部（类型/功能键栏）底色：比codeBg略亮/略暗一档，与内容区分层 */
    val cardHeaderBg: Color,
    /** 代码块高亮配色（深底palette默认Dark，浅底palette应传Light） */
    val codeHighlight: HighlightPalette = HighlightPalette.Dark,
)

/**
 * 助手气泡是固定深色，所以md内容也固定用深底配色
 * 若跟着主题走，浅色主题下onSurface是深色，深字压深气泡=表格内容看不见；分割线也会太浅
 */
internal val BubblePalette = InlinePalette(
    codeBg = Color(0xFF23262E),
    codeFg = Color(0xFFECECEC),
    inlineCodeBg = Color(0xFF2E3340),
    inlineCodeFg = Color(0xFFE6F0FF),
    langFg = Color(0xFF9AA3B2),
    lineNumberFg = Color(0xFF6C7480),
    link = Color(0xFF8AB4F8),
    quoteBar = Color(0xFF5A6270),
    quoteFg = Color(0xFFB4BAC4),
    outline = Color(0xFF444A57),
    cardHeaderBg = Color(0xFF2B3039),
)

/**
 * 用于跟随主题的浅色内容区（如更新弹窗）：深字压浅底
 * 与BubblePalette相对——后者服务于固定深色的助手气泡
 */
internal val LightContentPalette = InlinePalette(
    codeBg = Color(0xFFF2F3F5),
    codeFg = Color(0xFF24292F),
    inlineCodeBg = Color(0xFFEAECEF),
    inlineCodeFg = Color(0xFF24292F),
    langFg = Color(0xFF6A737D),
    lineNumberFg = Color(0xFF8C959F),
    link = Color(0xFF0969DA),
    quoteBar = Color(0xFF8C959F),
    quoteFg = Color(0xFF57606A),
    outline = Color(0xFFAFB8C1),
    cardHeaderBg = Color(0xFFE7E9ED),
    codeHighlight = HighlightPalette.Light,
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

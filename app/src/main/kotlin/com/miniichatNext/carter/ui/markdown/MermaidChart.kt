/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb

@Composable
internal fun MermaidChart(
    code: String,
    palette: InlinePalette,
    modifier: Modifier = Modifier,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    DiagramPreviewCard(code, svg = false, palette = palette, modifier = modifier)
}

internal fun buildMermaidHtml(
    code: String,
    palette: InlinePalette,
    dark: Boolean,
    maxHeightPx: Int = 176,
): String = buildDiagramHtml(
    code, svg = false, dark = dark,
    background = "#%06x".format(palette.codeBg.toArgb() and 0xffffff),
    foreground = "#%06x".format(palette.codeFg.toArgb() and 0xffffff),
)

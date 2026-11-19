/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown

import androidx.compose.runtime.Composable

@Composable
internal fun MermaidPreviewScreen(code: String, dark: Boolean, onBack: () -> Unit) {
    DiagramViewer(code, svg = false, dark = dark, onBack = onBack)
}

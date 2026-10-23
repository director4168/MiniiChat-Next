package com.miniichatNext.carter.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * 移植自rikkahub ChatMessage.kt的clipDrawing：只裁剪绘制，不设置触摸命中的图层轮廓。
 * 不等圆角的clip(shape)会产生Path轮廓；上游记录了坐标超过8192px时求交失精，
 * 导致长消息下半部的点击、长按、文本选择与横滑同时失效。保留视觉裁剪，绕开该命中路径。
 */
internal fun Modifier.clipDrawing(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
    }
    onDrawWithContent {
        clipPath(path) { this@onDrawWithContent.drawContent() }
    }
}

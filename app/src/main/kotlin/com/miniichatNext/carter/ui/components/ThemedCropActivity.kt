/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.components

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.yalantis.ucrop.UCropActivity

/** uCrop底部滚轮使用独立XML背景，不会自动继承Compose颜色。 */
class ThemedCropActivity : UCropActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (intent.getBooleanExtra(EXTRA_DARK, false)) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        } else {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        }
        super.onCreate(savedInstanceState)
        val background = intent.getIntExtra(EXTRA_SURFACE, 0xff202124.toInt())
        val content = intent.getIntExtra(EXTRA_CONTENT, 0xffeeeeee.toInt())
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)
        window.statusBarColor = background
        window.navigationBarColor = background
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        fun paint(view: View, insideControls: Boolean = false) {
            val name = if (view.id == View.NO_ID) "" else runCatching { resources.getResourceEntryName(view.id) }.getOrDefault("")
            val controls = insideControls || name in setOf("controls_wrapper", "wrapper_controls", "wrapper_states")
            if (name in setOf("controls_wrapper", "wrapper_controls", "wrapper_states", "layout_scale_wheel", "layout_rotate_wheel", "layout_aspect_ratio", "wrapper_scale_wheel", "wrapper_rotate_wheel", "wrapper_aspect_ratio", "text_view_scale", "text_view_rotate", "wrapper_rotate_by_angle", "wrapper_scale_by_angle")) {
                view.setBackgroundColor(background)
            }
            if (controls && view is TextView) view.setTextColor(content)
            if (view is ViewGroup) for (i in 0 until view.childCount) paint(view.getChildAt(i), controls)
        }
        paint(window.decorView)
    }

    companion object {
        const val EXTRA_SURFACE = "minichat.crop.surface"
        const val EXTRA_CONTENT = "minichat.crop.content"
        const val EXTRA_DARK = "minichat.crop.dark"
    }
}

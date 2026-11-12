package com.miniichatNext.carter.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.miniichatNext.carter.data.model.ToastTone
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.miniichatNext.carter.util.AppThemeOverrides

/**
 * toast外观与时长的唯一配置源。
 * 新增/调整toast样式只改这里，ToastStack和调试页的示例都从这里取，
 * 避免出现「调试页示例还是旧配色/旧时长」这类不一致。
 */
object ToastStyle {
    /** 普通提示停留 */
    const val SHOW_MS = 2000L
    /** 警告提示停留（比普通略久，便于看清） */
    const val WARNING_SHOW_MS = 3000L
    /** 错误提示信息量大，停留更久 */
    const val ERROR_SHOW_MS = 4000L
    /** 入场/出场动画时长 */
    const val ENTER_MS = 250
    const val EXIT_MS = 250
    /** 同时最多堆叠条数，超出移除最旧的 */
    const val MAX_STACK = 3
    /** 条目间距 */
    val SPACING_DP = 8

    // 容器配色固定深色：两种主题下观感一致，不跟随主题反转
    val CONTAINER = Color(0xE6222226)
    val CONTENT = Color(0xFFF2F1F7)
    val DISMISS = Color(0xFFF2F1F7)

    /** 普通提示的动作色 */
    val NORMAL_ACTION = Color(0xFF4DD0E1)
    /** 错误提示的动作色；与警告统一用toast标准色，靠停留时长区分 */
    val ERROR_ACTION = Color(0xFFB1A4FF)
    /** 警告动作色默认值；可在配色覆盖里用warningAction改 */
    val WARNING_ACTION_FALLBACK = Color(0xFFB1A4FF)

    fun showMs(tone: ToastTone): Long = when (tone) {
        ToastTone.Normal -> SHOW_MS
        ToastTone.Warning -> WARNING_SHOW_MS
        ToastTone.Error -> ERROR_SHOW_MS
    }
}

/** 单个toast的最终配色与时长 */
data class ToastSpec(
    val container: Color,
    val content: Color,
    val dismiss: Color,
    val action: Color,
    val showMs: Long,
)

/** 按tone解析一次，警告色支持AppThemeOverrides覆盖 */
@Composable
fun rememberToastSpec(tone: ToastTone): ToastSpec {
    val context = LocalContext.current
    val dark = LocalDarkMode.current
    val overrideVersion = AppThemeOverrides.version.intValue
    val overrides = androidx.compose.runtime.remember(overrideVersion, dark) {
        AppThemeOverrides.load(context)[if (dark) "dark" else "light"].orEmpty()
    }
    val warningAction = AppThemeOverrides.customColor(
        overrides,
        "warningAction",
        ToastStyle.WARNING_ACTION_FALLBACK,
    )
    val action = when (tone) {
        ToastTone.Normal -> ToastStyle.NORMAL_ACTION
        ToastTone.Warning -> warningAction
        ToastTone.Error -> warningAction
    }
    return ToastSpec(
        container = ToastStyle.CONTAINER,
        content = ToastStyle.CONTENT,
        dismiss = ToastStyle.DISMISS,
        action = action,
        showMs = ToastStyle.showMs(tone),
    )
}

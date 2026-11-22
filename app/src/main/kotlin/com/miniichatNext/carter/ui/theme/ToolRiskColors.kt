/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.miniichatNext.carter.util.AppThemeOverrides

/** 工具风险底色：Shizuku使用warning，Root使用error，均读取debug配色覆盖。 */
@Composable
internal fun toolRiskContainer(toolName: String): Color? {
    val context = LocalContext.current
    val dark = LocalDarkMode.current
    val version = AppThemeOverrides.version.intValue
    val overrides = remember(version, dark) {
        AppThemeOverrides.load(context)[if (dark) "dark" else "light"].orEmpty()
    }
    return when (toolName) {
        "execute_shell_root" -> AppThemeOverrides.customColor(
            overrides, "error_2", if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6)
        )
        "execute_shell_shizuku" -> AppThemeOverrides.customColor(
            overrides, "wrrning_2", if (dark) Color(0xFF4A3A12) else Color(0xFFFBEACC)
        )
        else -> null
    }
}

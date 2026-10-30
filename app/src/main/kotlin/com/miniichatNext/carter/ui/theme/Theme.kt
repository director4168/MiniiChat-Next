package com.miniichatNext.carter.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

internal val DefaultLightColors = lightColorScheme(
    primary = Color(0xFF6E5CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FF),
    onPrimaryContainer = Color(0xFF170B5C),
    
    secondary = Color(0xFF5B4FB8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5DFFF),
    onSecondaryContainer = Color(0xFF1A0F3E),
    
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF3F2F7),
    onSurfaceVariant = Color(0xFF47464F),
    
    surfaceTint = Color(0xFF6E5CFF),
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = Color(0xFFF2F0F4),
    
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE7E0EC),
    scrim = Color(0xFF000000),
    
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

internal val DefaultDarkColors = darkColorScheme(
    primary = Color(0xFFBDB1FF),
    onPrimary = Color(0xFF2A1A6B),
    primaryContainer = Color(0xFF4235A1),
    onPrimaryContainer = Color(0xFFE5DEFF),
    
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    
    surfaceTint = Color(0xFFBDB1FF),
    inverseSurface = Color(0xFFE4E1E6),
    inverseOnSurface = Color(0xFF303033),
    
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

/** 当前是否为深色主题（跟随themeMode的实际结果，而非系统深浅） */
val LocalDarkMode = staticCompositionLocalOf { false }

@Composable
fun MiniiChatTheme(
    themeMode: String = "system", // system | light | dark
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val dyn = if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            // Material You's generated dark scheme can produce very low-contrast
            // onSurfaceVariant against its surface, especially under cool-toned
            // wallpapers. Override the most legibility-critical roles to keep
            // chip labels, hints, and divider tints readable while still using
            // the wallpaper-derived hue for primary / containers.
            if (darkTheme) {
                dyn.copy(
                    background = Color(0xFF000000),
                    onBackground = Color(0xFFF2F1F7),
                    surface = Color(0xFF101013),
                    onSurface = Color(0xFFF2F1F7),
                    surfaceVariant = Color(0xFF24242C),
                    onSurfaceVariant = Color(0xFFC4C2D0),
                    outline = Color(0xFF3A3A45),
                    outlineVariant = Color(0xFF26262E)
                )
            } else dyn
        }
        darkTheme -> DefaultDarkColors
        else -> DefaultLightColors
    }
    // Debug配色覆盖：AppThemeOverrides.version变化时读取最新覆盖应用（保存后立即全局生效）
    val overrideVersion = com.miniichatNext.carter.util.AppThemeOverrides.version.intValue
    val themeCtx = LocalContext.current
    val colorsWithOverrides = remember(colors, overrideVersion, themeCtx) {
        val overrides = com.miniichatNext.carter.util.AppThemeOverrides.load(themeCtx)
        val mode = if (darkTheme) "dark" else "light"
        com.miniichatNext.carter.util.AppThemeOverrides.apply(colors, overrides[mode].orEmpty())
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalDarkMode provides darkTheme) {
        MaterialTheme(
            colorScheme = colorsWithOverrides,
            typography = AppTypography,
            content = content
        )
    }
}

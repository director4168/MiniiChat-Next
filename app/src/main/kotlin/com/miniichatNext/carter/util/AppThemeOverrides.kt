package com.miniichatNext.carter.util

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color

/**
 * 全局配色覆盖（Debug配色修改功能的数据层，第一百二十一轮）。
 *
 * - 覆盖按深浅两套分别存：Map<角色名, "#RRGGBB"|"#AARRGGBB">
 * - 持久化到SharedPreferences（JSON结构经[AppJson]解析）
 * - [version]递增驱动MiniiChatTheme重新加载（保存后立即生效）
 */
object AppThemeOverrides {
    private const val PREFS = "theme_overrides"
    private const val KEY_JSON = "json"

    /** 保存后递增：任何读取.intValue的组合会订阅并重载 */
    val version = mutableIntStateOf(0)

    internal val ROLE_GROUPS = ThemeColorRoles.groups
    val ALLOWED_ROLES = ThemeColorRoles.allowedRoles

    fun normalizeRoles(values: Map<String, String>): Map<String, String> = ThemeColorRoles.normalize(values)

    /** 当前覆盖：{"light": {role: hex}, "dark": {role: hex}}；解析失败返回空 */
    fun load(context: Context): Map<String, Map<String, String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return emptyMap()
        val obj = AppJson.parseObjectOrNull(raw) ?: return emptyMap()
        return buildMap {
            for (mode in listOf("light", "dark")) {
                val m = AppJson.obj(obj, mode) ?: continue
                val rawValues = m.keys.mapNotNull { role -> AppJson.str(m, role)?.let { role to it } }.toMap()
                put(mode, normalizeRoles(rawValues))
            }
        }
    }

    fun save(context: Context, light: Map<String, String>, dark: Map<String, String>) {
        val baseLight = currentSchemeHexes(dark = false)
        val baseDark = currentSchemeHexes(dark = true)
        fun diffed(values: Map<String, String>, base: Map<String, String>): Map<String, String> =
            normalizeRoles(values).filter { (role, value) -> parseHex(value) != parseHex(base.getValue(role)) }
        val json = org.json.JSONObject().apply {
            put("light", org.json.JSONObject(diffed(light, baseLight)))
            put("dark", org.json.JSONObject(diffed(dark, baseDark)))
        }.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, json).apply()
        version.intValue++
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_JSON).apply()
        version.intValue++
    }

    /** 把覆盖应用到ColorScheme上（hex非法的条目忽略） */
    fun apply(scheme: ColorScheme, overrides: Map<String, String>): ColorScheme {
        if (overrides.isEmpty()) return scheme
        val values = normalizeRoles(overrides)
        fun c(role: String): Color? = values[role]?.let { parseHex(it) }
        return scheme.copy(
            primary = c("primary") ?: scheme.primary,
            onPrimary = c("onPrimary") ?: scheme.onPrimary,
            primaryContainer = c("primaryContainer") ?: scheme.primaryContainer,
            onPrimaryContainer = c("onPrimaryContainer") ?: scheme.onPrimaryContainer,
            background = c("background") ?: scheme.background,
            onBackground = c("onBackground") ?: scheme.onBackground,
            surface = c("surface") ?: scheme.surface,
            onSurface = c("onSurface") ?: scheme.onSurface,
            surfaceVariant = c("surfaceVariant") ?: scheme.surfaceVariant,
            onSurfaceVariant = c("onSurfaceVariant") ?: scheme.onSurfaceVariant,
            outline = c("outline") ?: scheme.outline,
            outlineVariant = c("outlineVariant") ?: scheme.outlineVariant,
            error = c("error") ?: scheme.error,
            onError = c("onError") ?: scheme.onError,
        )
    }

    /**
     * 软件当前默认配色的角色hex表（ThemeColorsScreen预填用）。
     * 从Theme.kt的默认Light/Dark ColorScheme读取（dynamicColor壁纸取色是运行时派生，debug页以静态定义为基准）。
     */
    fun currentSchemeHexes(dark: Boolean): Map<String, String> {
        val scheme = if (dark) {
            com.miniichatNext.carter.ui.theme.DefaultDarkColors
        } else {
            com.miniichatNext.carter.ui.theme.DefaultLightColors
        }
        return buildMap {
            put("primary", toHex(scheme.primary))
            put("onPrimary", toHex(scheme.onPrimary))
            put("primaryContainer", toHex(scheme.primaryContainer))
            put("onPrimaryContainer", toHex(scheme.onPrimaryContainer))
            put("background", toHex(scheme.background))
            put("onBackground", toHex(scheme.onBackground))
            put("surface", toHex(scheme.surface))
            put("onSurface", toHex(scheme.onSurface))
            put("surfaceVariant", toHex(scheme.surfaceVariant))
            put("onSurfaceVariant", toHex(scheme.onSurfaceVariant))
            put("outline", toHex(scheme.outline))
            put("outlineVariant", toHex(scheme.outlineVariant))
            put("error", toHex(scheme.error))
            put("onError", toHex(scheme.onError))
            put("warning", if (dark) "#FFCA5C" else "#8A5A00")
            put("warningAction", "#B1A4FF")
            put("messageJumperBackground", "#CC82B1FF")
            put("messageJumperContent", "#102A56")
            put("logSearchHighlight", toHex(scheme.onPrimaryContainer))
            put("logSearchBackground", toHex(scheme.primaryContainer))
        }
    }


    fun customColor(overrides: Map<String, String>, role: String, fallback: Color): Color =
        normalizeRoles(overrides)[ThemeColorRoles.canonicalId(role)]?.let(::parseHex) ?: fallback

    /** "#RRGGBB"/"#AARRGGBB"→Color；非法返回null */
    fun parseHex(s: String): Color? = runCatching {
        if (!ThemeColorRoles.isValidHex(s)) return null
        val v = s.removePrefix("#").removePrefix("0x").removePrefix("0X")
        when (v.length) {
            6 -> Color(0xFF000000L or v.toLong(16))
            8 -> Color(v.toLong(16))
            else -> return null
        }
    }.getOrNull()

    /** Color→"#RRGGBB"（alpha FF时）或"#AARRGGBB" */
    fun toHex(c: Color): String {
        val argb = c.toArgbLong()
        return if ((argb ushr 24) == 0xFFL) {
            String.format("#%06X", argb and 0xFFFFFF)
        } else {
            String.format("#%08X", argb)
        }
    }

    private fun Color.toArgbLong(): Long =
        ((alpha * 255).toInt().toLong() shl 24) or
            ((red * 255).toInt().toLong() shl 16) or
            ((green * 255).toInt().toLong() shl 8) or
            (blue * 255).toInt().toLong()
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

/** 配色编辑、持久化和旧版ID迁移共用的角色表。 */
internal object ThemeColorRoles {
    val groups = linkedMapOf(
        "primary" to listOf("primary", "onPrimary", "primaryContainer", "onPrimaryContainer"),
        "surfaces" to listOf("background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant"),
        "outlines" to listOf("outline", "outlineVariant"),
        "status" to listOf("warning", "error", "onError", "warningAction", "wrrning_2", "error_2"),
        "messageNavigation" to listOf("messageJumperBackground", "messageJumperContent"),
        "logSearch" to listOf("logSearchHighlight", "logSearchBackground"),
    )
    val allowedRoles = groups.values.flatten()
    private val legacyIds = mapOf(
        "workspaceWarning" to "warning",
        "workspaceDanger" to "error",
        "toastWarningAction" to "warningAction",
        "toolShizukuBackground" to "wrrning_2",
        "toolRootBackground" to "error_2",
    )

    fun canonicalId(role: String): String = legacyIds[role] ?: role

    fun isValidHex(value: String): Boolean {
        val hex = value.removePrefix("#").removePrefix("0x").removePrefix("0X")
        return hex.length in listOf(6, 8) && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** 手动输入：只保留十六进制字符，转大写，补#，最多8位。 */
    fun sanitizeInput(value: String): String {
        val body = value.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            .uppercase()
            .take(8)
        return "#$body"
    }

    /** 保存：补足6位RRGGBB或8位AARRGGBB；空值按黑色处理。 */
    fun completeForSave(value: String): String {
        val body = sanitizeInput(value).removePrefix("#")
        return if (body.length <= 6) {
            "#" + body.padEnd(6, '0')
        } else {
            "#" + body.take(8).padEnd(8, '0')
        }
    }

    /** 新ID的有效值优先；只有旧ID时迁移，非法值不遮盖可用的旧值。 */
    fun normalize(values: Map<String, String>): Map<String, String> = buildMap {
        allowedRoles.forEach { role ->
            val value = values[role]?.takeIf(::isValidHex)
                ?: legacyIds.entries.firstNotNullOfOrNull { (old, current) ->
                    if (current == role) values[old]?.takeIf(::isValidHex) else null
                }
            if (value != null) put(role, sanitizeInput(value))
        }
    }
}

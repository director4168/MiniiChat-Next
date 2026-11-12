/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.mcp

internal data class EffectiveToolPermission(val enabled: Boolean, val requireApproval: Boolean)

internal fun resolveToolPermission(
    toolName: String,
    serverId: String,
    permissions: List<McpToolPermission>,
    defaultApproval: Boolean,
    categoryApproval: Boolean? = null,
): EffectiveToolPermission {
    val permission = permissions.firstOrNull { it.toolName == toolName && it.serverId == serverId }
    return EffectiveToolPermission(
        enabled = permission?.enabled ?: true,
        requireApproval = categoryApproval ?: permission?.takeUnless { it.inheritApproval }?.requireApproval ?: defaultApproval,
    )
}

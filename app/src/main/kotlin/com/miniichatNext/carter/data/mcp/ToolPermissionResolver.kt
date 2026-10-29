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
        requireApproval = categoryApproval ?: permission?.requireApproval ?: defaultApproval,
    )
}

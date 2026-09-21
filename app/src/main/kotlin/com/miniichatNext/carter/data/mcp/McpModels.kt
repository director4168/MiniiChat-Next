package com.miniichatNext.carter.data.mcp

import kotlinx.serialization.Serializable

/** STREAMABLE_HTTP: 单端点POST，可返回SSE流; SSE: 旧版先GET取endpoint再POST */
enum class McpTransport { STREAMABLE_HTTP, SSE }

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val transport: String = McpTransport.STREAMABLE_HTTP.name,
    val enabled: Boolean = true,
    val requireApproval: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val toolsFetched: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun tp(): McpTransport = runCatching { McpTransport.valueOf(transport) }
        .getOrDefault(McpTransport.STREAMABLE_HTTP)
}

/** serverId为空表示内置工具 */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: String = "",
    val serverId: String = "",
    val builtIn: Boolean = false
)

@Serializable
data class McpToolPermission(
    val toolName: String,
    val serverId: String = "",
    val enabled: Boolean = true,
    val requireApproval: Boolean = true
)

data class McpPendingApproval(
    val requestId: String,
    val serverId: String,
    val toolName: String,
    val arguments: String,
    val preview: String
)

data class McpToolResult(
    val ok: Boolean,
    val content: String,
    val error: String? = null
)

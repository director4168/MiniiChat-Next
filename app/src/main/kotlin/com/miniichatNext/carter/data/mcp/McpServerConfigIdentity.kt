/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.mcp

import java.net.URI
import java.util.Locale

internal fun normalizedMcpUrl(raw: String): String {
    val uri = URI(raw.trim()).normalize()
    require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Invalid MCP HTTP URL" }
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val port = uri.port.takeUnless { it == -1 || it == 80 && scheme == "http" || it == 443 && scheme == "https" }
    val host = uri.host.lowercase(Locale.ROOT).let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
    val user = uri.rawUserInfo?.let { "$it@" }.orEmpty()
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    return "$scheme://$user$host${port?.let { ":$it" }.orEmpty()}$path${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}

internal fun sameMcpConnection(first: McpServerConfig, second: McpServerConfig): Boolean =
    runCatching { normalizedMcpUrl(first.url) == normalizedMcpUrl(second.url) }.getOrDefault(false) &&
        first.transport == second.transport && first.headers == second.headers

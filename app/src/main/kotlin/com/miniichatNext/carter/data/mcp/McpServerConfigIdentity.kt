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
    val uri = checkedMcpUri(raw.trim()).normalize()
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val port = uri.port.takeUnless { it == -1 || it == 80 && scheme == "http" || it == 443 && scheme == "https" }
    val host = uri.host.lowercase(Locale.ROOT).let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    return "$scheme://$host${port?.let { ":$it" }.orEmpty()}$path${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}

internal fun sameMcpConnection(first: McpServerConfig, second: McpServerConfig): Boolean =
    runCatching { normalizedMcpUrl(first.url) == normalizedMcpUrl(second.url) }.getOrDefault(false) &&
        first.transport == second.transport && first.headers == second.headers

internal fun sameMcpSession(first: McpServerConfig, second: McpServerConfig): Boolean =
    first.id == second.id && first.connectionRevision == second.connectionRevision && sameMcpConnection(first, second)

internal fun McpServerConfig.toolBinding(): String = "$id:$connectionRevision"

internal fun resolveMcpEndpoint(configuredUrl: String, advertised: String): String {
    val base = checkedMcpUri(configuredUrl)
    require(advertised.isNotBlank() && advertised.length <= 4096) { "Invalid MCP endpoint" }
    val endpoint = checkedMcpUri(base.resolve(advertised.trim()).toString())
    require(base.scheme.equals(endpoint.scheme, true) && base.host.equals(endpoint.host, true) &&
        effectivePort(base) == effectivePort(endpoint)) { "MCP SSE endpoint must use the configured origin" }
    return endpoint.toASCIIString()
}

internal fun checkedMcpUri(raw: String): URI = URI(raw).also { uri ->
    require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
        "Invalid MCP HTTP URL"
    }
}

private fun effectivePort(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

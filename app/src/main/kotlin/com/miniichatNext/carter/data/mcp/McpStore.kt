/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.util.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.mcpDataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_configs")

class McpStore(private val context: Context) {
    private val serversKey = stringPreferencesKey("mcp_servers_json")
    private val toolsKey = stringPreferencesKey("mcp_tools_json")
    private val permsKey = stringPreferencesKey("mcp_perms_json")

    private val json get() = AppJson.json

    val serversFlow: Flow<List<McpServerConfig>> = context.mcpDataStore.data.map { prefs ->
        decode(prefs[serversKey], McpServerConfig.serializer())
    }

    val toolsFlow: Flow<List<McpTool>> = context.mcpDataStore.data.map { prefs ->
        decode(prefs[toolsKey], McpTool.serializer())
    }

    val permsFlow: Flow<List<McpToolPermission>> = context.mcpDataStore.data.map { prefs ->
        decode(prefs[permsKey], McpToolPermission.serializer())
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun snapshot(): List<McpServerConfig> = serversFlow.first()
    suspend fun toolsSnapshot(): List<McpTool> = toolsFlow.first()
    suspend fun permsSnapshot(): List<McpToolPermission> = permsFlow.first()

    suspend fun saveServers(servers: List<McpServerConfig>) {
        context.mcpDataStore.edit { prefs ->
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers)
        }
    }

    suspend fun saveTools(tools: List<McpTool>) {
        context.mcpDataStore.edit { prefs ->
            prefs[toolsKey] = json.encodeToString(ListSerializer(McpTool.serializer()), tools)
        }
    }

    suspend fun savePerms(perms: List<McpToolPermission>) {
        context.mcpDataStore.edit { prefs ->
            prefs[permsKey] = json.encodeToString(ListSerializer(McpToolPermission.serializer()), perms)
        }
    }

    suspend fun upsertServer(server: McpServerConfig) {
        context.mcpDataStore.edit { prefs ->
            val cur = decode(prefs[serversKey], McpServerConfig.serializer()).toMutableList()
            val index = cur.indexOfFirst { it.id == server.id }
            if (index >= 0) cur[index] = server else cur.add(server)
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), cur)
        }
    }

    suspend fun saveEditor(server: McpServerConfig): McpServerConfig {
        val url = normalizedMcpUrl(server.url)
        var saved = server.copy(url = url)
        context.mcpDataStore.edit { prefs ->
            val current = decode(prefs[serversKey], McpServerConfig.serializer()).toMutableList()
            val index = current.indexOfFirst { it.id == server.id }.takeIf { it >= 0 }
                ?: current.indexOfFirst { sameMcpConnection(it, saved) }
            if (index >= 0) {
                val old = current[index]
                val same = sameMcpConnection(old, saved)
                saved = saved.copy(id = old.id, createdAt = old.createdAt,
                    toolsFetched = same && old.toolsFetched, syncError = if (same) old.syncError else null,
                    lastSyncAt = if (same) old.lastSyncAt else null)
                current[index] = saved
            } else current.add(saved.copy(toolsFetched = false, syncError = null, lastSyncAt = null).also { saved = it })
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), current)
        }
        return saved
    }

    suspend fun recordSyncFailure(server: McpServerConfig, error: String): Boolean {
        var recorded = false
        context.mcpDataStore.edit { prefs ->
            val current = decode(prefs[serversKey], McpServerConfig.serializer())
            val updated = current.map {
                if (it.id == server.id && sameMcpConnection(it, server)) {
                    recorded = true
                    it.copy(syncError = error.take(1000))
                } else it
            }
            if (recorded) prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), updated)
        }
        return recorded
    }

    suspend fun setServerEnabled(id: String, enabled: Boolean) {
        context.mcpDataStore.edit { prefs ->
            val current = decode(prefs[serversKey], McpServerConfig.serializer())
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()),
                current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    suspend fun commitTools(server: McpServerConfig, tools: List<McpTool>): Boolean {
        var committed = false
        context.mcpDataStore.edit { prefs ->
            val servers = decode(prefs[serversKey], McpServerConfig.serializer()).toMutableList()
            val index = servers.indexOfFirst { it.id == server.id }
            if (index < 0) return@edit
            val current = servers[index]
            if (!sameMcpConnection(current, server)) return@edit
            servers[index] = current.copy(toolsFetched = true, syncError = null, lastSyncAt = System.currentTimeMillis())
            val old = decode(prefs[toolsKey], McpTool.serializer()).filterNot { it.serverId == server.id }
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers)
            prefs[toolsKey] = json.encodeToString(ListSerializer(McpTool.serializer()), old + tools.map { it.copy(serverId = server.id) })
            committed = true
        }
        return committed
    }

    suspend fun deleteServer(id: String) {
        context.mcpDataStore.edit { prefs ->
            val servers = decode(prefs[serversKey], McpServerConfig.serializer()).filterNot { it.id == id }
            val tools = decode(prefs[toolsKey], McpTool.serializer()).filterNot { it.serverId == id }
            val permissions = decode(prefs[permsKey], McpToolPermission.serializer()).filterNot { it.serverId == id }
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers)
            prefs[toolsKey] = json.encodeToString(ListSerializer(McpTool.serializer()), tools)
            prefs[permsKey] = json.encodeToString(ListSerializer(McpToolPermission.serializer()), permissions)
        }
    }

    suspend fun replaceToolsForServer(serverId: String, tools: List<McpTool>) {
        context.mcpDataStore.edit { prefs ->
            val cur = decode(prefs[toolsKey], McpTool.serializer()).filterNot { it.serverId == serverId }
            prefs[toolsKey] = json.encodeToString(ListSerializer(McpTool.serializer()), cur + tools.map { it.copy(serverId = serverId) })
        }
    }

    suspend fun setToolPermission(serverId: String, toolName: String, enabled: Boolean, requireApproval: Boolean, inheritApproval: Boolean = false) {
        context.mcpDataStore.edit { prefs ->
            val cur = decode(prefs[permsKey], McpToolPermission.serializer()).toMutableList()
            val index = cur.indexOfFirst { it.serverId == serverId && it.toolName == toolName }
            val next = McpToolPermission(toolName = toolName, serverId = serverId,
                enabled = enabled, requireApproval = requireApproval, inheritApproval = inheritApproval)
            if (index >= 0) cur[index] = next else cur.add(next)
            prefs[permsKey] = json.encodeToString(ListSerializer(McpToolPermission.serializer()), cur)
        }
    }

    suspend fun exportFullJson(): String = json.encodeToString(
        McpBackupPayload.serializer(),
        McpBackupPayload(snapshot(), toolsSnapshot(), permsSnapshot())
    )

    suspend fun importFullJson(raw: String) {
        val payload = json.decodeFromString(McpBackupPayload.serializer(), raw)
        context.mcpDataStore.edit { prefs ->
            prefs[serversKey] = json.encodeToString(ListSerializer(McpServerConfig.serializer()), payload.servers)
            prefs[toolsKey] = json.encodeToString(ListSerializer(McpTool.serializer()), payload.tools)
            prefs[permsKey] = json.encodeToString(ListSerializer(McpToolPermission.serializer()), payload.perms)
        }
    }
}

@Serializable
private data class McpBackupPayload(
    val servers: List<McpServerConfig> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val perms: List<McpToolPermission> = emptyList()
)

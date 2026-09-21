package com.miniichatNext.carter.data.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

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
        val cur = snapshot().toMutableList()
        val idx = cur.indexOfFirst { it.id == server.id }
        if (idx >= 0) cur[idx] = server else cur.add(server)
        saveServers(cur)
    }

    suspend fun deleteServer(id: String) {
        saveServers(snapshot().filterNot { it.id == id })
        saveTools(toolsSnapshot().filterNot { it.serverId == id })
        savePerms(permsSnapshot().filterNot { it.serverId == id })
    }

    suspend fun replaceToolsForServer(serverId: String, tools: List<McpTool>) {
        val cur = toolsSnapshot().filterNot { it.serverId == serverId }.toMutableList()
        cur.addAll(tools.map { it.copy(serverId = serverId) })
        saveTools(cur)
    }

    suspend fun setToolPermission(
        serverId: String,
        toolName: String,
        enabled: Boolean,
        requireApproval: Boolean
    ) {
        val cur = permsSnapshot().toMutableList()
        val idx = cur.indexOfFirst { it.serverId == serverId && it.toolName == toolName }
        val newPerm = McpToolPermission(
            serverId = serverId,
            toolName = toolName,
            enabled = enabled,
            requireApproval = requireApproval
        )
        if (idx >= 0) cur[idx] = newPerm else cur.add(newPerm)
        savePerms(cur)
    }

    suspend fun exportFullJson(): String = json.encodeToString(
        McpBackupPayload.serializer(),
        McpBackupPayload(snapshot(), toolsSnapshot(), permsSnapshot())
    )

    suspend fun importFullJson(raw: String) {
        runCatching {
            val payload = json.decodeFromString(McpBackupPayload.serializer(), raw)
            saveServers(payload.servers)
            saveTools(payload.tools)
            savePerms(payload.perms)
        }
    }
}

@Serializable
private data class McpBackupPayload(
    val servers: List<McpServerConfig> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val perms: List<McpToolPermission> = emptyList()
)

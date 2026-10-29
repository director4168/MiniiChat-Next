package com.miniichatNext.carter.data.mcp

import com.miniichatNext.carter.util.AppJson

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

    suspend fun setToolPermission(serverId: String, toolName: String, enabled: Boolean, requireApproval: Boolean) {
        context.mcpDataStore.edit { prefs ->
            val cur = decode(prefs[permsKey], McpToolPermission.serializer()).toMutableList()
            val index = cur.indexOfFirst { it.serverId == serverId && it.toolName == toolName }
            val next = McpToolPermission(toolName = toolName, serverId = serverId,
                enabled = enabled, requireApproval = requireApproval)
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

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.backup

import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.store.SqlPreference
import com.miniichatNext.carter.data.store.encodePreferences
import com.miniichatNext.carter.data.store.migration.LegacyPreferencesMigration
import com.miniichatNext.carter.data.util.ApiKeyCrypto
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.util.AppJson
import java.io.File
import kotlinx.serialization.builtins.ListSerializer

internal object LegacyConfigurationBackup {
    private val paths = LegacyPreferencesMigration.namespaces.flatMap { name ->
        listOf("data/", "data/datastore/", "data/files/datastore/").map { "$it$name.preferences_pb" to name }
    }.toMap()

    fun namespace(path: String): String? = paths[path]

    suspend fun read(stage: File, indexes: List<BackupFileIndex>): Map<String, Map<String, SqlPreference>> = buildMap {
        for (index in indexes) {
            val domain = checkNotNull(namespace(index.path))
            val entries = LegacyPreferencesMigration.read(File(stage, index.path)).encodePreferences()
            put(domain, validate(domain, entries))
        }
    }

    private fun validate(domain: String, entries: Map<String, SqlPreference>): Map<String, SqlPreference> {
        val json = AppJson.json
        fun field(key: String) = entries[key]?.let { require(it.type == "string"); it.value.takeIf(String::isNotBlank) }
        fun credential(value: String): String {
            if (value.isEmpty()) return value
            require(ApiKeyCrypto.decrypt(value).isNotEmpty()) { "Legacy credentials belong to a different Android installation" }
            return ApiKeyCrypto.encrypt(value).also { check(it.isNotEmpty()) }
        }
        return when (domain) {
            "providers" -> {
                val providers = field("providers_json")?.let { json.decodeFromString<List<ProviderConfig>>(it) } ?: return entries
                require(providers.map { it.id }.distinct().size == providers.size)
                val protected = providers.map { provider -> provider.copy(
                    apiKey = credential(provider.apiKey), customHeaders = provider.customHeaders.mapValues { credential(it.value) },
                    models = provider.models.map { model -> model.copy(
                        apiKey = model.apiKey?.let(::credential), customHeaders = model.customHeaders.mapValues { credential(it.value) },
                    ) },
                ) }
                entries + ("providers_json" to SqlPreference("string", json.encodeToString(ListSerializer(ProviderConfig.serializer()), protected)))
            }
            "assistants" -> entries.also { field("assistants_json")?.let { json.decodeFromString<List<Assistant>>(it) } }
            "user_profile" -> entries.also { field("user_profile_json")?.let { json.decodeFromString<UserProfile>(it) } }
            "mcp_configs" -> entries.also {
                field("mcp_servers_json")?.let { json.decodeFromString<List<McpServerConfig>>(it) }
                field("mcp_tools_json")?.let { json.decodeFromString<List<McpTool>>(it) }
                field("mcp_perms_json")?.let { json.decodeFromString<List<McpToolPermission>>(it) }
            }
            "skills" -> entries.also { field("skills_json")?.let { json.decodeFromString<List<Skill>>(it) } }
            "workspaces" -> entries.also {
                field("workspaces_json")?.let { raw ->
                    val workspaces = json.decodeFromString<List<WorkspaceEntity>>(raw)
                    require(workspaces.all { it.root.matches(Regex("[A-Za-z0-9._-]+")) && it.root !in setOf(".", "..") }) { "Invalid workspace root" }
                }
            }
            else -> entries
        }
    }
}

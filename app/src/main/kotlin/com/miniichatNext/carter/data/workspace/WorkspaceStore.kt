package com.miniichatNext.carter.data.workspace

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

private val Context.workspaceDataStore: DataStore<Preferences> by preferencesDataStore(name = "workspaces")

@Serializable
data class WorkspaceEntity(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    /** toolName -> needsApproval的用户覆盖项 */
    val toolApprovals: String = "{}",
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}

class WorkspaceStore(private val context: Context) {
    private val key = stringPreferencesKey("workspaces_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val workspacesFlow: Flow<List<WorkspaceEntity>> = context.workspaceDataStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(WorkspaceEntity.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun snapshot(): List<WorkspaceEntity> = workspacesFlow.first()

    suspend fun save(list: List<WorkspaceEntity>) {
        context.workspaceDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(WorkspaceEntity.serializer()), list)
        }
    }

    suspend fun upsert(entity: WorkspaceEntity) {
        val list = snapshot().toMutableList()
        val idx = list.indexOfFirst { it.id == entity.id }
        if (idx >= 0) list[idx] = entity else list.add(entity)
        save(list)
    }

    suspend fun delete(id: String) = save(snapshot().filterNot { it.id == id })
}

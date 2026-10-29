package com.miniichatNext.carter.data.workspace

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
    /** toolName->needsApproval的用户覆盖项 */
    val toolApprovals: String = "{}",
    /** shell兼容模式（PROOT_NO_SECCOMP=1，部分设备proot的seccomp问题开这个） */
    val shellCompatibilityMode: Boolean = false,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        AppJson.json.decodeFromString<Map<String, Boolean>>(toolApprovals)
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
    private val json get() = AppJson.json

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

    private fun read(prefs: Preferences): List<WorkspaceEntity> = prefs[key]?.let {
        json.decodeFromString(ListSerializer(WorkspaceEntity.serializer()), it)
    }.orEmpty()

    suspend fun upsert(entity: WorkspaceEntity) {
        context.workspaceDataStore.edit { prefs ->
            val list = read(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == entity.id }
            if (index >= 0) list[index] = entity else list.add(entity)
            prefs[key] = json.encodeToString(ListSerializer(WorkspaceEntity.serializer()), list)
        }
    }

    suspend fun update(id: String, transform: (WorkspaceEntity) -> WorkspaceEntity): WorkspaceEntity? {
        var updated: WorkspaceEntity? = null
        context.workspaceDataStore.edit { prefs ->
            val list = read(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            val next = transform(list[index])
            require(next.id == id) { "Workspace id changed during update" }
            list[index] = next
            prefs[key] = json.encodeToString(ListSerializer(WorkspaceEntity.serializer()), list)
            updated = next
        }
        return updated
    }

    suspend fun delete(id: String) {
        context.workspaceDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(WorkspaceEntity.serializer()), read(prefs).filterNot { it.id == id })
        }
    }
}

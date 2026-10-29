package com.miniichatNext.carter.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.AssistantPresets
import com.miniichatNext.carter.debug.DebugLog

private val Context.assistantsDataStore: DataStore<Preferences> by preferencesDataStore(name = "assistants")

class AssistantStore(private val context: Context) {
    private val key = stringPreferencesKey("assistants_json")
    private val json get() = AppJson.json

    val assistantsFlow: Flow<List<Assistant>> =
        context.assistantsDataStore.data.map { prefs ->
            val raw = prefs[key]
            if (raw.isNullOrBlank()) AssistantPresets.defaults()
            else runCatching {
                json.decodeFromString(ListSerializer(Assistant.serializer()), raw)
            }.getOrDefault(AssistantPresets.defaults())
        }

    suspend fun snapshot(): List<Assistant> = assistantsFlow.first()

    suspend fun save(list: List<Assistant>) {
        context.assistantsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(Assistant.serializer()), list)
        }
    }

    private fun read(prefs: Preferences): List<Assistant> {
        val raw = prefs[key]
        return if (raw.isNullOrBlank()) AssistantPresets.defaults()
            else json.decodeFromString(ListSerializer(Assistant.serializer()), raw)
    }

    suspend fun updateAll(transform: (List<Assistant>) -> List<Assistant>) {
        context.assistantsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(Assistant.serializer()), transform(read(prefs)))
        }
    }

    suspend fun upsert(assistant: Assistant) {
        updateAll { current ->
            current.toMutableList().also { list ->
                val index = list.indexOfFirst { it.id == assistant.id }
                if (index >= 0) list[index] = assistant else list.add(assistant)
            }
        }
    }

    suspend fun update(id: String, transform: (Assistant) -> Assistant) {
        updateAll { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    suspend fun delete(id: String) { updateAll { list -> list.filterNot { it.id == id } } }
}

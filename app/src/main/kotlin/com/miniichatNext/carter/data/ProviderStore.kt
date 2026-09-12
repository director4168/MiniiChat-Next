package com.miniichatNext.carter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import com.miniichatNext.carter.util.ApiKeyCrypto

private val Context.providersDataStore: DataStore<Preferences> by preferencesDataStore(name = "providers")

class ProviderStore(private val context: Context) {
    private val key = stringPreferencesKey("providers_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val providersFlow: Flow<List<ProviderConfig>> =
        context.providersDataStore.data.map { prefs ->
            val raw = prefs[key] ?: return@map emptyList()
            runCatching { decode(raw) }
                .getOrDefault(emptyList())
                // API Key 在磁盘上是密文，读出来给上层用之前解密
                .map { it.copy(apiKey = ApiKeyCrypto.decrypt(it.apiKey)) }
        }

    suspend fun snapshot(): List<ProviderConfig> = providersFlow.first()

    suspend fun save(list: List<ProviderConfig>) {
        com.miniichatNext.carter.Debug.DebugLog.d(
            "Store", "providers.save count=${list.size} ids=${list.map { it.id }}"
        )
        val encoded = list.map { it.copy(apiKey = ApiKeyCrypto.encrypt(it.apiKey)) }
        context.providersDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), encoded)
        }
    }

    suspend fun upsert(p: ProviderConfig) {
        val list = snapshot().toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        save(list)
    }

    suspend fun delete(id: String) {
        save(snapshot().filterNot { it.id == id })
    }

    private fun decode(raw: String): List<ProviderConfig> {
        val array = json.parseToJsonElement(raw).jsonArray
        return array.mapNotNull { item ->
            val obj = item.jsonObject
            val modelElement = obj["models"]
            val isLegacyStringList = modelElement is JsonArray &&
                modelElement.all { it is JsonPrimitive && it.jsonPrimitive.isString }
            if (modelElement == null || isLegacyStringList) {
                migrateProvider(obj)
            } else {
                runCatching {
                    json.decodeFromJsonElement(ProviderConfig.serializer(), item)
                }.getOrNull()
            }
        }
    }

    private fun migrateProvider(obj: JsonObject): ProviderConfig? {
        return runCatching {
            val models = obj["models"]?.jsonArray?.mapNotNull { el ->
                val s = el.jsonPrimitive.content
                ModelConfig(modelId = s, displayName = s)
            } ?: emptyList()
            val base = json.decodeFromJsonElement(ProviderConfig.serializer(), obj)
            base.copy(models = models)
        }.getOrNull()
    }
}

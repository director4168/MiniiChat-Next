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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.data.util.ApiKeyCrypto
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.debug.DebugLog

private val Context.providersDataStore: DataStore<Preferences> by preferencesDataStore(name = "providers")

class ProviderStore(private val context: Context) {
    private val key = stringPreferencesKey("providers_json")
    private val json get() = AppJson.json

    val providersFlow: Flow<List<ProviderConfig>> =
        context.providersDataStore.data.map { prefs ->
            val raw = prefs[key] ?: return@map emptyList()
            runCatching { decode(raw) }
                .getOrDefault(emptyList())
                // API Key在磁盘上是密文，读出来给上层用之前解密
                .map(::decryptProvider)
        }

    suspend fun snapshot(): List<ProviderConfig> = providersFlow.first()

    private fun storedProviders(prefs: Preferences): List<ProviderConfig> =
        prefs[key]?.let { decode(it) }.orEmpty()

    private fun protectHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (_, value) ->
        val encrypted = ApiKeyCrypto.encrypt(value)
        if (value.isNotEmpty() && encrypted.isEmpty()) error("Header encryption is unavailable")
        encrypted
    }

    private fun revealHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (_, value) ->
        ApiKeyCrypto.decrypt(value)
    }

    private fun protectProvider(provider: ProviderConfig, previous: ProviderConfig?): ProviderConfig {
        val preserveOldKey = !provider.apiKeyCleared && provider.apiKey.isEmpty() &&
            !previous?.apiKey.isNullOrEmpty() && ApiKeyCrypto.decrypt(previous!!.apiKey).isEmpty()
        val key = when {
            provider.apiKeyCleared -> ""
            preserveOldKey -> previous!!.apiKey
            provider.apiKey.isEmpty() -> ""
            else -> ApiKeyCrypto.encrypt(provider.apiKey).also { if (it.isEmpty()) error("API key encryption is unavailable") }
        }
        val previousModels = previous?.models.orEmpty().associateBy { it.modelId }
        val models = provider.models.map { model ->
            val old = previousModels[model.modelId]
            val modelKey = when {
                model.apiKeyCleared -> ""
                model.apiKey.isNullOrEmpty() && !old?.apiKey.isNullOrEmpty() && ApiKeyCrypto.decrypt(old!!.apiKey!!).isEmpty() -> old!!.apiKey
                model.apiKey.isNullOrEmpty() -> model.apiKey
                else -> ApiKeyCrypto.encrypt(model.apiKey).also { if (it.isEmpty()) error("Model API key encryption is unavailable") }
            }
            model.copy(apiKey = modelKey, customHeaders = protectHeaders(model.customHeaders))
        }
        return provider.copy(apiKey = key, customHeaders = protectHeaders(provider.customHeaders), models = models)
    }

    private fun decryptProvider(provider: ProviderConfig): ProviderConfig = provider.copy(
        apiKey = ApiKeyCrypto.decrypt(provider.apiKey),
        customHeaders = revealHeaders(provider.customHeaders),
        models = provider.models.map { model -> model.copy(
            apiKey = model.apiKey?.let(ApiKeyCrypto::decrypt),
            customHeaders = revealHeaders(model.customHeaders),
        ) },
    )

    private fun encodeProviders(list: List<ProviderConfig>, previous: List<ProviderConfig>): String {
        val storedById = previous.associateBy { it.id }
        val encoded = list.map { provider ->
            val old = storedById[provider.id]
            protectProvider(provider, old)
        }
        return json.encodeToString(ListSerializer(ProviderConfig.serializer()), encoded)
    }

    suspend fun save(list: List<ProviderConfig>) {
        context.providersDataStore.edit { prefs -> prefs[key] = encodeProviders(list, storedProviders(prefs)) }
    }

    suspend fun upsert(provider: ProviderConfig) {
        context.providersDataStore.edit { prefs ->
            val previous = storedProviders(prefs)
            val list = previous.toMutableList()
            val index = list.indexOfFirst { it.id == provider.id }
            if (index >= 0) list[index] = provider else list.add(provider)
            prefs[key] = encodeProviders(list, previous)
        }
    }

    suspend fun update(id: String, transform: (ProviderConfig) -> ProviderConfig): ProviderConfig? {
        var updated: ProviderConfig? = null
        context.providersDataStore.edit { prefs ->
            val previous = storedProviders(prefs)
            val index = previous.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            val current = decryptProvider(previous[index])
            val next = transform(current)
            require(next.id == id) { "Provider id changed during update" }
            prefs[key] = encodeProviders(previous.toMutableList().also { it[index] = next }, previous)
            updated = next
        }
        return updated
    }

    suspend fun delete(id: String) {
        context.providersDataStore.edit { prefs ->
            val previous = storedProviders(prefs)
            prefs[key] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), previous.filterNot { it.id == id })
        }
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

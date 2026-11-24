/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.util.ApiKeyCrypto
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Context.providersPreferences get() = SqlPreferences(this, "providers")

class ProviderStore(private val context: Context) {
    private val key = stringPreferencesKey("providers_json")
    private val json get() = AppJson.json

    val providersFlow: Flow<List<ProviderConfig>> =
        context.providersPreferences.data.map { prefs ->
            val raw = prefs[key] ?: return@map emptyList()
            runCatching { decode(raw, allowInvalidEntries = true) }
                .getOrDefault(emptyList())
                // API Key在磁盘上是密文，读出来给上层用之前解密
                .map(::decryptProvider)
        }

    suspend fun snapshot(): List<ProviderConfig> = providersFlow.first()

    suspend fun backupSnapshot(): List<ProviderConfig> {
        val stored = storedProviders(context.providersPreferences.data.first())
        fun checkKey(value: String?) {
            if (!value.isNullOrEmpty()) require(ApiKeyCrypto.decrypt(value).isNotEmpty()) { "Stored credential cannot be decrypted; re-enter it before backing up" }
        }
        stored.forEach { provider ->
            checkKey(provider.apiKey)
            provider.customHeaders.values.forEach(::checkKey)
            provider.models.forEach { model ->
                checkKey(model.apiKey)
                model.customHeaders.values.forEach(::checkKey)
            }
        }
        return stored.map(::decryptProvider)
    }

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
        context.providersPreferences.edit { prefs -> prefs[key] = encodeProviders(list, storedProviders(prefs)) }
    }

    suspend fun reorder(ids: List<String>) {
        context.providersPreferences.edit { prefs ->
            val current = storedProviders(prefs)
            val byId = current.associateBy { it.id }
            val ordered = ids.distinct().mapNotNull { byId[it] } + current.filterNot { it.id in ids }
            prefs[key] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), ordered)
        }
    }

    suspend fun upsert(provider: ProviderConfig) {
        context.providersPreferences.edit { prefs ->
            val previous = storedProviders(prefs)
            val list = previous.toMutableList()
            val index = list.indexOfFirst { it.id == provider.id }
            if (index >= 0) list[index] = provider else list.add(provider)
            prefs[key] = encodeProviders(list, previous)
        }
    }

    suspend fun update(id: String, transform: (ProviderConfig) -> ProviderConfig): ProviderConfig? {
        var updated: ProviderConfig? = null
        context.providersPreferences.edit { prefs ->
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
        context.providersPreferences.edit { prefs ->
            val previous = storedProviders(prefs)
            prefs[key] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), previous.filterNot { it.id == id })
        }
    }

    private fun decode(raw: String, allowInvalidEntries: Boolean = false): List<ProviderConfig> {
        return json.parseToJsonElement(raw).jsonArray.mapIndexedNotNull { index, item ->
            try {
                val obj = item.jsonObject
                val models = obj["models"]
                if (models is JsonArray && models.any { it is JsonPrimitive }) {
                    val converted = models.map { model ->
                        if (model is JsonPrimitive && model.isString) {
                            json.encodeToJsonElement(ModelConfig.serializer(), ModelConfig(modelId = model.content, displayName = model.content))
                        } else model
                    }
                    json.decodeFromJsonElement(ProviderConfig.serializer(), JsonObject(obj + ("models" to JsonArray(converted))))
                } else {
                    json.decodeFromJsonElement(ProviderConfig.serializer(), obj)
                }
            } catch (error: IllegalArgumentException) {
                if (!allowInvalidEntries) throw IllegalStateException("Invalid provider configuration at entry ${index + 1}; original retained", error)
                DebugLog.w("Store", "Skipping invalid provider entry ${index + 1}; original retained")
                null
            }
        }
    }
}

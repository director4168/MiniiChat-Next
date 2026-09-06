package com.miniichatNext.carter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val activeProviderId: String = "",
    val activeModel: String = "",
    val activeAssistantId: String = "default",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",
    // "providerId::modelId" references for auxiliary features.
    val ocrModel: String = "",
    val titleModel: String = "",
    val compressModel: String = "",
    val suggestionModel: String = "",
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0
)

class SettingsRepository(private val context: Context) {

    // Guards read-modify-write against concurrent updates (e.g. two coroutines
    // adjusting different settings at once would otherwise clobber each other).
    private val updateMutex = Mutex()

    private object Keys {
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val ASSISTANT = stringPreferencesKey("active_assistant_id")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val TOTAL_PROMPT = intPreferencesKey("total_prompt_tokens")
        val TOTAL_COMPLETION = intPreferencesKey("total_completion_tokens")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        activeProviderId = p[Keys.PROVIDER] ?: "",
        activeModel = p[Keys.MODEL] ?: "",
        activeAssistantId = p[Keys.ASSISTANT] ?: "default",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        ocrModel = p[Keys.OCR_MODEL] ?: "",
        titleModel = p[Keys.TITLE_MODEL] ?: "",
        compressModel = p[Keys.COMPRESS_MODEL] ?: "",
        suggestionModel = p[Keys.SUGGESTION_MODEL] ?: "",
        totalPromptTokens = p[Keys.TOTAL_PROMPT] ?: 0,
        totalCompletionTokens = p[Keys.TOTAL_COMPLETION] ?: 0
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) = updateMutex.withLock {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.ASSISTANT] = next.activeAssistantId
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.OCR_MODEL] = next.ocrModel
            p[Keys.TITLE_MODEL] = next.titleModel
            p[Keys.COMPRESS_MODEL] = next.compressModel
            p[Keys.SUGGESTION_MODEL] = next.suggestionModel
            p[Keys.TOTAL_PROMPT] = next.totalPromptTokens
            p[Keys.TOTAL_COMPLETION] = next.totalCompletionTokens
        }
    }

    suspend fun addTokenUsage(usage: TokenUsage) {
        if (usage.promptTokens == 0 && usage.completionTokens == 0) return
        update {
            it.copy(
                totalPromptTokens = it.totalPromptTokens + usage.promptTokens,
                totalCompletionTokens = it.totalCompletionTokens + usage.completionTokens
            )
        }
    }
}

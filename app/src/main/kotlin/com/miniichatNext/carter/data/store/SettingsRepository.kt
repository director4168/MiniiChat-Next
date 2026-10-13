package com.miniichatNext.carter.data.store

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.miniichatNext.carter.data.model.TokenUsage

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
    // providerId::modelId引用辅助功能
    val ocrModel: String = "",
    /** OCR触发模式：auto=仅纯文本模型/always=所有带图消息（视觉模型也附加文字）/off=禁用 */
    val ocrMode: String = "auto",
    val titleModel: String = "",
    val compressModel: String = "",
    val suggestionModel: String = "",
    /** 辅助模型无法使用时回退到当前正在使用的模型 */
    val auxFallbackToActive: Boolean = false,
    /** 调试悬浮球：显示在所有页面之上的日志等级快速切换入口 */
    val debugLogBall: Boolean = false,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    /** 启动时自动检查更新 */
    val autoCheckUpdate: Boolean = true,
    /** 用户已忽略的版本号集合（持久化为逗号分隔字符串） */
    val ignoredUpdateVersions: Set<String> = emptySet(),
)

class SettingsRepository(private val context: Context) {

    // Guard对并发更新进行读-改-写（例如两个协程同时调整不同的设置，否则会互相覆盖）
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
        val DEBUG_LOG_BALL = booleanPreferencesKey("debug_log_ball")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
    val OCR_MODE = stringPreferencesKey("ocr_mode")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val AUX_FALLBACK = booleanPreferencesKey("aux_fallback_to_active")
        val TOTAL_PROMPT = intPreferencesKey("total_prompt_tokens")
        val TOTAL_COMPLETION = intPreferencesKey("total_completion_tokens")
        val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        val IGNORED_UPDATE_VERSIONS = stringPreferencesKey("ignored_update_versions")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p: Preferences -> read(p) }

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
        ocrMode = p[Keys.OCR_MODE] ?: "auto",
        titleModel = p[Keys.TITLE_MODEL] ?: "",
        compressModel = p[Keys.COMPRESS_MODEL] ?: "",
        suggestionModel = p[Keys.SUGGESTION_MODEL] ?: "",
        auxFallbackToActive = p[Keys.AUX_FALLBACK] ?: false,
        debugLogBall = p[Keys.DEBUG_LOG_BALL] ?: false,
        totalPromptTokens = p[Keys.TOTAL_PROMPT] ?: 0,
        totalCompletionTokens = p[Keys.TOTAL_COMPLETION] ?: 0,
        autoCheckUpdate = p[Keys.AUTO_CHECK_UPDATE] ?: true,
        ignoredUpdateVersions = (p[Keys.IGNORED_UPDATE_VERSIONS] ?: "")
            .split(',').filter { it.isNotBlank() }.toSet(),
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
            p[Keys.OCR_MODE] = next.ocrMode
            p[Keys.TITLE_MODEL] = next.titleModel
            p[Keys.COMPRESS_MODEL] = next.compressModel
            p[Keys.SUGGESTION_MODEL] = next.suggestionModel
            p[Keys.AUX_FALLBACK] = next.auxFallbackToActive
            p[Keys.TOTAL_PROMPT] = next.totalPromptTokens
            p[Keys.TOTAL_COMPLETION] = next.totalCompletionTokens
            p[Keys.AUTO_CHECK_UPDATE] = next.autoCheckUpdate
            p[Keys.IGNORED_UPDATE_VERSIONS] = next.ignoredUpdateVersions.joinToString(",")
            p[Keys.DEBUG_LOG_BALL] = next.debugLogBall
        }
    }

    // 更新检测相关便捷方法
    suspend fun setAutoCheckUpdate(enabled: Boolean) {
        update { it.copy(autoCheckUpdate = enabled) }
    }

    suspend fun ignoreUpdateVersion(version: String) {
        if (version.isBlank()) return
        update { it.copy(ignoredUpdateVersions = it.ignoredUpdateVersions + version) }
    }

    suspend fun clearIgnoredUpdateVersions() {
        update { it.copy(ignoredUpdateVersions = emptySet()) }
    }

    /** 一次性读出"启动时自动检查"+"已忽略版本集合"，给Application.onCreate用 */
    suspend fun snapshotForUpdate(): Pair<Boolean, Set<String>> {
        val s = settings.first()
        return s.autoCheckUpdate to s.ignoredUpdateVersions
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

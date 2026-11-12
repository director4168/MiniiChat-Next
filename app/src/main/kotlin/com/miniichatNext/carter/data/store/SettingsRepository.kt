/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.data.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

const val COMPRESS_MODEL_CONVERSATION = "@conversation"

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
data class AppSettings(
    val launchCount: Long = 0,
    val autoNewChatOnLaunch: Boolean = true,
    val fontSizeSp: Float = 15f,
    val showReasoningCard: Boolean = true,
    val showCardTokenUsage: Boolean = true,
    val sendOnEnter: Boolean = false,
    val pasteAsAttachment: Boolean = false,
    val activeProviderId: String = "",
    val activeModel: String = "",
    val activeAssistantId: String = "default",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",
    /** Markdown代码块是否自动换行 */
    val codeWrap: Boolean = true,
    /** Markdown代码块超过十行是否默认折叠 */
    val codeFold: Boolean = true,
    /** 是否渲染LaTeX数学公式 */
    val latexEnabled: Boolean = true,
    // 格式providerId::modelId
    val ocrModel: String = "",
    /** OCR触发模式：auto=仅纯文本模型/always=所有带图消息（视觉模型也附加文字）/off=禁用 */
    val ocrMode: String = "auto",
    val titleModel: String = COMPRESS_MODEL_CONVERSATION,
    val compressModel: String = COMPRESS_MODEL_CONVERSATION,
    val suggestionModel: String = COMPRESS_MODEL_CONVERSATION,
    /** 辅助模型无法使用时回退到当前正在使用的模型 */
    val auxFallbackToActive: Boolean = false,
    /** 调试悬浮球：显示在所有页面之上的日志等级快速切换入口 */
    val debugLogBall: Boolean = false,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalCachedTokens: Long = 0,
    /** 启动时自动检查更新 */
    val autoCheckUpdate: Boolean = true,
    /** 用户已忽略的版本号集合（持久化为逗号分隔字符串） */
    val ignoredUpdateVersions: Set<String> = emptySet(),
)

class SettingsRepository(private val context: Context) {

    // Guard对并发更新进行读-改-写（例如两个协程同时调整不同的设置，否则会互相覆盖）
    private val updateMutex = Mutex()

    private object Keys {
        val LAUNCH_COUNT = longPreferencesKey("launch_count")
        val AUTO_NEW_CHAT = booleanPreferencesKey("pref_auto_new_chat")
        val FONT_SIZE = floatPreferencesKey("pref_font_size")
        val SHOW_REASONING = booleanPreferencesKey("pref_show_reasoning")
        val SHOW_USAGE = booleanPreferencesKey("pref_show_usage")
        val SEND_ENTER = booleanPreferencesKey("pref_send_enter")
        val PASTE_ATTACHMENT = booleanPreferencesKey("pref_paste_attachment")
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val ASSISTANT = stringPreferencesKey("active_assistant_id")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val CODE_WRAP = booleanPreferencesKey("code_wrap")
        val CODE_FOLD = booleanPreferencesKey("code_fold")
        val LATEX_ENABLED = booleanPreferencesKey("latex_enabled")
        val DEBUG_LOG_BALL = booleanPreferencesKey("debug_log_ball")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
    val OCR_MODE = stringPreferencesKey("ocr_mode")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val AUX_FALLBACK = booleanPreferencesKey("aux_fallback_to_active")
        val TOTAL_PROMPT_LEGACY = intPreferencesKey("total_prompt_tokens")
        val TOTAL_PROMPT = longPreferencesKey("total_prompt_tokens_long")
        val TOTAL_CACHED = longPreferencesKey("total_cached_tokens_long")
        val TOTAL_COMPLETION_LEGACY = intPreferencesKey("total_completion_tokens")
        val TOTAL_COMPLETION = longPreferencesKey("total_completion_tokens_long")
        val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        val IGNORED_UPDATE_VERSIONS = stringPreferencesKey("ignored_update_versions")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p: Preferences -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        launchCount = (p[Keys.LAUNCH_COUNT] ?: 0L).coerceAtLeast(0L),
        autoNewChatOnLaunch = p[Keys.AUTO_NEW_CHAT] ?: true,
        fontSizeSp = p[Keys.FONT_SIZE] ?: 15f,
        showReasoningCard = p[Keys.SHOW_REASONING] ?: true,
        showCardTokenUsage = p[Keys.SHOW_USAGE] ?: true,
        sendOnEnter = p[Keys.SEND_ENTER] ?: false,
        pasteAsAttachment = p[Keys.PASTE_ATTACHMENT] ?: false,
        activeProviderId = p[Keys.PROVIDER] ?: "",
        activeModel = p[Keys.MODEL] ?: "",
        activeAssistantId = p[Keys.ASSISTANT] ?: "default",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        codeWrap = p[Keys.CODE_WRAP] ?: true,
        codeFold = p[Keys.CODE_FOLD] ?: true,
        latexEnabled = p[Keys.LATEX_ENABLED] ?: true,
        ocrModel = p[Keys.OCR_MODEL] ?: "",
        ocrMode = p[Keys.OCR_MODE] ?: "auto",
        titleModel = p[Keys.TITLE_MODEL] ?: COMPRESS_MODEL_CONVERSATION,
        compressModel = p[Keys.COMPRESS_MODEL] ?: COMPRESS_MODEL_CONVERSATION,
        suggestionModel = p[Keys.SUGGESTION_MODEL] ?: COMPRESS_MODEL_CONVERSATION,
        auxFallbackToActive = p[Keys.AUX_FALLBACK] ?: false,
        debugLogBall = p[Keys.DEBUG_LOG_BALL] ?: false,
        totalCachedTokens = p[Keys.TOTAL_CACHED] ?: 0L,
        totalPromptTokens = p[Keys.TOTAL_PROMPT] ?: p[Keys.TOTAL_PROMPT_LEGACY]?.toLong() ?: 0L,
        totalCompletionTokens = p[Keys.TOTAL_COMPLETION] ?: p[Keys.TOTAL_COMPLETION_LEGACY]?.toLong() ?: 0L,
        autoCheckUpdate = p[Keys.AUTO_CHECK_UPDATE] ?: true,
        ignoredUpdateVersions = (p[Keys.IGNORED_UPDATE_VERSIONS] ?: "")
            .split(',').filter { it.isNotBlank() }.toSet(),
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) = updateMutex.withLock {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.LAUNCH_COUNT] = next.launchCount.coerceAtLeast(0L)
            p[Keys.AUTO_NEW_CHAT] = next.autoNewChatOnLaunch
            p[Keys.FONT_SIZE] = next.fontSizeSp
            p[Keys.SHOW_REASONING] = next.showReasoningCard
            p[Keys.SHOW_USAGE] = next.showCardTokenUsage
            p[Keys.SEND_ENTER] = next.sendOnEnter
            p[Keys.PASTE_ATTACHMENT] = next.pasteAsAttachment
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.ASSISTANT] = next.activeAssistantId
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.CODE_WRAP] = next.codeWrap
            p[Keys.CODE_FOLD] = next.codeFold
            p[Keys.LATEX_ENABLED] = next.latexEnabled
            p[Keys.OCR_MODEL] = next.ocrModel
            p[Keys.OCR_MODE] = next.ocrMode
            p[Keys.TITLE_MODEL] = next.titleModel
            p[Keys.COMPRESS_MODEL] = next.compressModel
            p[Keys.SUGGESTION_MODEL] = next.suggestionModel
            p[Keys.AUX_FALLBACK] = next.auxFallbackToActive
            p[Keys.TOTAL_CACHED] = next.totalCachedTokens
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
                totalCompletionTokens = it.totalCompletionTokens + usage.completionTokens,
                totalCachedTokens = it.totalCachedTokens + usage.cachedTokens
            )
        }
    }
}

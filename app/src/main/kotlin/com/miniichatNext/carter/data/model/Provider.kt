package com.miniichatNext.carter.data.model

import androidx.annotation.StringRes
import com.miniichatNext.carter.R
import kotlinx.serialization.Serializable

enum class ProviderType { OPENAI, CLAUDE }

enum class ThinkingLevel(val budgetTokens: Int, val effort: String?) {
    OFF(0, null),

    /** 让模型自己决定推理强度（Anthropic走adaptive，OpenAI发effort=auto） */
    AUTO(-1, "auto"),

    LOW(2048, "low"),
    MEDIUM(8192, "medium"),
    HIGH(16384, "high");

    val isEnabled: Boolean get() = this != OFF

    val apiValue: String get() = name.lowercase()
}

/** Anthropic提示缓存的TTL。null表示用服务端默认（5分钟） */
enum class PromptCacheTtl(val apiValue: String?) {
    FIVE_MINUTES(null),
    ONE_HOUR("1h")
}

@Serializable
data class ModelConfig(
    val modelId: String = "",
    val displayName: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val extraBody: Map<String, String> = emptyMap(),
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    /** null=跟随服务商。之前默认OFF导致服务商级设置永远不生效 */
    val thinkingLevel: String? = null,
    /**
     * 该模型是否支持推理参数。默认true（保持既有行为不变）；
     * 关掉后不再往请求里塞thinking/reasoning_effort，
     * 避免对不支持推理的模型（gpt-4o/视觉/embedding）被网关拒绝
     */
    val supportsReasoning: Boolean = true,
    /** 该模型是否支持工具调用（function calling）；关掉后不向模型提供tools */
    val supportsToolCall: Boolean = true,
    /** 该模型是否支持图片输入（vision）；关掉后发送时跳过图片附件 */
    val supportsImageInput: Boolean = true,
    /** 模型级连接覆盖（优先于服务商级；null/空=跟随服务商） */
    val baseUrl: String? = null,
    /** 模型级API路径（如/v1/chat/completions）；null=跟随服务商chatCompletionsPath */
    val apiPath: String? = null,
    /** 模型级API Key；null/空=跟随服务商 */
    val apiKey: String? = null,
    /** 模型级Response API偏好：null=跟随服务商（仅对OPENAI类型有意义） */
    val responseApi: Boolean? = null,
    /** 模型级提示缓存偏好：null=跟随服务商（仅对CLAUDE类型有意义） */
    val promptCache: Boolean? = null,
    /** 模型级缓存TTL：null=跟随服务商 */
    val promptCacheTtl: String? = null,
) {
    fun thinking(): ThinkingLevel = thinkingLevel
        ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
        ?: ThinkingLevel.OFF

    /** 是否有任何「单独设置」的字段（用于UI外框加深提示） */
    fun hasOverride(): Boolean =
        thinkingLevel != null || temperature != null || maxTokens != null
            || customHeaders.isNotEmpty() || extraBody.isNotEmpty()
            || !supportsReasoning || !supportsToolCall || !supportsImageInput
            || !baseUrl.isNullOrBlank() || !apiPath.isNullOrBlank() || !apiKey.isNullOrBlank()
            || responseApi != null || promptCache != null || promptCacheTtl != null

    /** 与全新默认配置等价（无任何覆盖）——用于保存时清理空配置，避免误高亮 */
    fun isDefault(): Boolean = !hasOverride()
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<ModelConfig> = emptyList(),
    val customHeaders: Map<String, String> = emptyMap(),
    val extraBody: Map<String, String> = emptyMap(),
    val providerType: String = ProviderType.OPENAI.name,
    val thinkingLevel: String = ThinkingLevel.OFF.name,
    val maxTokens: Int = 8192,
    val useResponseApi: Boolean = false,
    /** Anthropic提示缓存：服务商级默认值，单个模型可覆盖 */
    val promptCache: Boolean = false,
    val promptCacheTtl: String = PromptCacheTtl.FIVE_MINUTES.name,
    val chatCompletionsPath: String = "/chat/completions",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun type(): ProviderType = runCatching { ProviderType.valueOf(providerType) }
        .getOrDefault(ProviderType.OPENAI)

    fun thinking(): ThinkingLevel = runCatching { ThinkingLevel.valueOf(thinkingLevel) }
        .getOrDefault(ThinkingLevel.OFF)

    fun cacheTtl(): PromptCacheTtl = runCatching { PromptCacheTtl.valueOf(promptCacheTtl) }
        .getOrDefault(PromptCacheTtl.FIVE_MINUTES)

    fun model(id: String): ModelConfig? = models.firstOrNull { it.modelId == id }

    fun modelIds(): List<String> = models.map { it.modelId.ifBlank { it.displayName } }
        .filter { it.isNotBlank() }

    // 生效值：模型级覆盖优先，未覆盖(null)时回落到服务商级

    fun effectiveThinking(modelId: String): ThinkingLevel =
        model(modelId)?.thinkingLevel?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: thinking()

    fun supportsReasoning(modelId: String): Boolean =
        model(modelId)?.supportsReasoning ?: true

    fun supportsToolCall(modelId: String): Boolean =
        model(modelId)?.supportsToolCall ?: true

    fun supportsImageInput(modelId: String): Boolean =
        model(modelId)?.supportsImageInput ?: true

    /**
     * Response API/提示缓存是「对话级+按服务商」的覆盖：
     * 对话里在加号菜单设过就用对话的值，否则用服务商配置
     */
    fun effectiveResponseApi(modelId: String, override: Boolean?): Boolean =
        override
            ?: model(modelId)?.responseApi
            ?: useResponseApi

    fun effectivePromptCache(modelId: String, override: Boolean?): Boolean =
        override
            ?: model(modelId)?.promptCache
            ?: promptCache

    fun effectiveCacheTtl(modelId: String, overrideTtl: String?): PromptCacheTtl =
        overrideTtl?.let { runCatching { PromptCacheTtl.valueOf(it) }.getOrNull() }
            ?: model(modelId)?.promptCacheTtl?.let { runCatching { PromptCacheTtl.valueOf(it) }.getOrNull() }
            ?: cacheTtl()

    /** 模型级连接参数：baseUrl/apiPath/apiKey覆盖（null=跟随服务商） */
    fun effectiveBaseUrl(modelId: String): String =
        model(modelId)?.baseUrl?.takeIf { it.isNotBlank() } ?: baseUrl

    fun effectiveApiPath(modelId: String): String =
        model(modelId)?.apiPath?.takeIf { it.isNotBlank() } ?: chatCompletionsPath

    fun effectiveApiKey(modelId: String): String =
        model(modelId)?.apiKey?.takeIf { it.isNotBlank() } ?: apiKey

    /** 有没有显式覆盖过某项设置（用于UI显示「跟随服务商」） */
    fun hasModelOverride(modelId: String, which: String): Boolean {
        val m = model(modelId) ?: return false
        return when (which) {
            "thinking" -> m.thinkingLevel != null
            else -> false
        }
    }
}

object ProviderPresets {
    data class Preset(
        val name: String,
        val baseUrl: String,
        @StringRes val hintRes: Int,
        val path: String = "/chat/completions",
        val type: ProviderType = ProviderType.OPENAI
    )

    /*
     * 模型预设
     * 追加条目：Anthropic格式加type=ProviderType.CLAUDE；
     * 端点不是/chat/completions的加path
     * 介绍文案放字符串资源（hintRes）
     */
    val all: List<Preset> = listOf(
            Preset("OpenAI", "https://api.openai.com/v1",
                R.string.provider_preset_openai_hint),

            Preset("Anthropic Claude", "https://api.anthropic.com/v1",
                R.string.provider_preset_claude_hint,
                type = ProviderType.CLAUDE), // Anthropic兼容格式，必须这样选

            Preset("OpenRouter", "https://openrouter.ai/api/v1",
                R.string.provider_preset_openrouter_hint),

            Preset("DeepSeek", "https://api.deepseek.com",
                R.string.provider_preset_deepseek_hint),

            Preset("xAI", "https://api.x.ai/v1",
                R.string.provider_preset_xai_hint,
                path = "/responses"), // 非标端点：xAI走/responses而不是/chat/completions

            Preset("Mistral", "https://api.mistral.ai/v1",
                R.string.provider_preset_mistral_hint),

            Preset("Minimax", "https://api.minimaxi.com/anthropic/v1",
                R.string.provider_preset_minimax_hint,
                type = ProviderType.CLAUDE),

            Preset("Gemini (OpenAI shim)",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                R.string.provider_preset_gemini_hint),

            Preset("SiliconFlow",
                "https://api.siliconflow.cn/v1",
                R.string.provider_preset_siliconflow_hint),
        )
}

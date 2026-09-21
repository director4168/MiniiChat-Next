package com.miniichatNext.carter.data.model

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

/** Anthropic提示缓存的TTL。null表示用服务端默认（5分钟）。 */
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
     * 关掉后不再往请求里塞thinking / reasoning_effort，
     * 避免对不支持推理的模型（gpt-4o / 视觉 / embedding）被网关拒绝。
     */
    val supportsReasoning: Boolean = true
) {
    fun thinking(): ThinkingLevel = thinkingLevel
        ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
        ?: ThinkingLevel.OFF
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

    fun model(id: String): ModelConfig? = models.firstOrNull {
        it.modelId == id || it.displayName == id
    }

    fun modelIds(): List<String> = models.map { it.modelId.ifBlank { it.displayName } }
        .filter { it.isNotBlank() }

    // ---- 生效值：模型级覆盖优先，未覆盖(null)时回落到服务商级 ----

    fun effectiveThinking(modelId: String): ThinkingLevel =
        model(modelId)?.thinkingLevel?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: thinking()

    fun supportsReasoning(modelId: String): Boolean =
        model(modelId)?.supportsReasoning ?: true

    /**
     * Response API / 提示缓存是「对话级 + 按服务商」的覆盖：
     * 对话里在加号菜单设过就用对话的值，否则用服务商配置。
     */
    fun effectiveResponseApi(override: Boolean?): Boolean = override ?: useResponseApi

    fun effectivePromptCache(override: Boolean?): Boolean = override ?: promptCache

    fun effectiveCacheTtl(overrideTtl: String?): PromptCacheTtl =
        overrideTtl?.let { runCatching { PromptCacheTtl.valueOf(it) }.getOrNull() } ?: cacheTtl()

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
        val hint: String,
        val path: String = "/chat/completions",
        val type: ProviderType = ProviderType.OPENAI
    )

    /*
     * 下面的这些是提供的模型预设
     *
     * 如果是克劳德格式需要追加:
     * type = ProviderType.CLAUDE),
     *
     * 如果提供商的API路径不是/chat/completions，需要自定义，那么可以追加:
     * path = "[API路径]"
     *
     * 追加提供商基础格式：
     * Preset("[提供商名称]", "[提供商的baseUrl]",
     *     "[介绍]"),
     */
    val all: List<Preset> = listOf(
            Preset("OpenAI", "https://api.openai.com/v1",
                "OpenAI官方API，提供GPT系列模型"),

            Preset("Anthropic Claude", "https://api.anthropic.com/v1",
                "Anthropic（Claude克劳德）官方API，提供Claude系列模型",
                type = ProviderType.CLAUDE),

            Preset("OpenRouter", "https://openrouter.ai/api/v1",
                "第三方提供商，提供OpenAI / Anthropic / Gemini"),

            Preset("DeepSeek", "https://api.deepseek.com",
                "Deepseek官方API，提供deepseek系列模型"),

            Preset("xAI", "https://api.x.ai/v1",
                "马斯克产业旗下xAI，提供Grok（格ro克）系列模型",
                path = "/responses"),

            Preset("Mistral", "https://api.mistral.ai/v1",
                "提供Mistral模型"),

            Preset("Minimax", "https://api.minimaxi.com/anthropic/v1",
                "Minimax官方API，提供minimax系列模型",
                type = ProviderType.CLAUDE),

            Preset("Gemini (OpenAI shim)",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "谷歌旗下的Gemini官方API，提供Gemini系列模型"),

            Preset("SiliconFlow",
                "https://api.siliconflow.cn/v1",
                "国内聚合，速度快"),

            Preset("AK AI Gateway",
                "https://aigateway.akile.ai/v1",
                "第三方中转站，提供GPT、Claude系列模型，注册地址：https://aigateway.akile.ai/register?aff=CFJYTVZ4QSEN"),
            // CFJYTVZ4QSEN是我的邀请码，如果愿意还请使用一下
        )
}

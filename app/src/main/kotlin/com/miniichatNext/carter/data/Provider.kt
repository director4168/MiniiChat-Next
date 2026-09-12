package com.miniichatNext.carter.data

import kotlinx.serialization.Serializable

enum class ProviderType { OPENAI, CLAUDE }

enum class ThinkingLevel(val budgetTokens: Int) {
    OFF(0),
    LOW(2048),
    MEDIUM(8192),
    HIGH(16384);

    val apiValue: String get() = name.lowercase()
}

@Serializable
data class ModelConfig(
    val modelId: String = "",
    val displayName: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val extraBody: Map<String, String> = emptyMap(),
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val thinkingLevel: String = ThinkingLevel.OFF.name,
    val useResponseApi: Boolean = false
) {
    fun thinking(): ThinkingLevel = runCatching { ThinkingLevel.valueOf(thinkingLevel) }
        .getOrDefault(ThinkingLevel.OFF)
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
    val chatCompletionsPath: String = "/chat/completions",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun type(): ProviderType = runCatching { ProviderType.valueOf(providerType) }
        .getOrDefault(ProviderType.OPENAI)

    fun thinking(): ThinkingLevel = runCatching { ThinkingLevel.valueOf(thinkingLevel) }
        .getOrDefault(ThinkingLevel.OFF)

    fun model(id: String): ModelConfig? = models.firstOrNull {
        it.modelId == id || it.displayName == id
    }

    fun modelIds(): List<String> = models.map { it.modelId.ifBlank { it.displayName } }
        .filter { it.isNotBlank() }
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
            // CFJYTVZ4QSEN  是我的邀请码，如果愿意还请使用一下
        )
}

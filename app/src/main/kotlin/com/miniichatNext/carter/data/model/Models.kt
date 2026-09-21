package com.miniichatNext.carter.data.model

import kotlinx.serialization.Serializable

enum class Role { user, assistant, system, tool }

object ToolInvocationState {
    const val PENDING_APPROVAL = "pending_approval"
    const val EXECUTING = "executing"
    const val RUNNING = "executing"
    const val SUCCESS = "success"
    const val ERROR = "error"
    const val REJECTED = "rejected"
}

@Serializable
data class Attachment(
    /** 唯一id —— LazyRow/列的key必须用它，不能用uri。
     * 同一个物理文件被不同picker/ContentProvider暴露可能产生不同uri字符串；
     * 用index后缀看似避开了key冲突，但**只要list出现两个uri完全相同的项**（重复点开、混用两个picker等）
     * 任意一处把uri当key都会IllegalArgumentException崩。
     * 给附件一个本会话内唯一的id才是真的抗打。 */
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,
    val uri: String,
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0,
    /** 文件内容SHA-256（hex）。附件从picker导入后即拥有，用于跨URI路径去重
     * （ColorOS文件管理器vs MediaStore指向同一物理文件时，uri字符串不同但sha一致）。 */
    val sha256: String = "",
)

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0,
    val tokensPerSecond: Float = 0f
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        durationMs = durationMs + other.durationMs,
        tokensPerSecond = if (durationMs + other.durationMs > 0)
            ((completionTokens + other.completionTokens) * 1000f) / (durationMs + other.durationMs)
        else 0f
    )
}

/** 消息内记录的一次工具调用与执行结果 */
@Serializable
data class ToolInvocation(
    val callId: String,
    val toolName: String,
    val arguments: String = "",
    val state: String = ToolInvocationState.EXECUTING,
    val result: String = "",
    val error: String? = null
) {
    val argumentsJson: String get() = arguments
    val resultOutput: String get() = result
}

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val usage: TokenUsage? = null,
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val reasoningContent: String? = null,
    val toolCallId: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class ProviderOverride(
    /** null = 该对话不对这个服务商做覆盖，用服务商配置 */
    val responseApi: Boolean? = null,
    val promptCache: Boolean? = null,
    val promptCacheTtl: String? = null
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val assistantId: String = "default",
    val messages: List<Message> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val suggestionSeed: Int = 0,
    val suggestionPages: Map<Int, List<String>> = emptyMap(),
    /** 对话级工具覆盖：未覆盖时跟随助手配置（聊天页「+」菜单的工作区/MCP弹窗写入） */
    val toolWorkspaceOverridden: Boolean = false,
    val toolWorkspaceId: String? = null,
    val toolMcpOverridden: Boolean = false,
    val toolMcpEnabled: Set<String> = emptySet(),
    /**
     * 对话级「按服务商」的开关覆盖：key=providerId。
     *
     * 只作用于本对话；对该服务商下的**所有模型**生效（切模型不会丢），
     * 但不影响其他服务商（换服务商自然就不生效）。
     * 没记录的key = 跟随服务商配置。
     */
    val providerOverrides: Map<String, ProviderOverride> = emptyMap(),
    /**
     * 对话级思考等级（覆盖服务商/模型级）。
     * null = 自动（AUTO，等价于让服务商决定，旧数据兼容）
     */
    val thinkingLevel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 该对话的「思考等级」。null = 自动（AUTO，等价于「不干预」）。
     *
     * 取代之前的ModelConfig/ProviderConfig双层thinkingLevel —— 现在只有这一处。
     */
    fun effectiveThinking(): ThinkingLevel = thinkingLevel
        ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
        ?: ThinkingLevel.AUTO
}

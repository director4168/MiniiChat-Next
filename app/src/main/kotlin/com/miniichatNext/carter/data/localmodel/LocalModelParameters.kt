/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import kotlinx.serialization.Serializable

@Serializable
internal data class LocalModelParameters(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 512,
    val contextSize: Int = 2048,
    val threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4),
    val seed: Int = -1,
) {
    fun isValid(): Boolean = temperature in 0f..2f && topP > 0f && topP <= 1f &&
        topK in 0..200 && repeatPenalty in 0.5f..2f && contextSize in 256..16384 &&
        maxTokens in 1..(contextSize / 2) && threads in 1..16 && seed >= -1
}

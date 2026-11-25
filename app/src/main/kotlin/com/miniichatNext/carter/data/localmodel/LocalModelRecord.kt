/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import kotlinx.serialization.Serializable

@Serializable
internal data class LocalModelRecord(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val parameters: LocalModelParameters = LocalModelParameters(),
)

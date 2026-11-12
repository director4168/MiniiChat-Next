/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.skills

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String = "",
    val body: String = "",
    val enabled: Boolean = true,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

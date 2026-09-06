package com.miniichatNext.carter.data

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String = "",
    val body: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

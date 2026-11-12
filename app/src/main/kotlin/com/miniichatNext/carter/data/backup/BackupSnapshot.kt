/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.backup

import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.store.AppSettings
import kotlinx.serialization.Serializable

@Serializable
data class BackupSnapshot(
    val settings: AppSettings,
    val providers: List<ProviderConfig>,
    val assistants: List<Assistant>,
    val conversations: List<Conversation>,
    val profile: UserProfile,
    val mcp: String,
    val theme: String = "",
)


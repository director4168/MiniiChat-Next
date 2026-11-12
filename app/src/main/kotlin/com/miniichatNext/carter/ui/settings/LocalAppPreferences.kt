/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.miniichatNext.carter.data.store.AppSettings

val LocalAppPreferences = staticCompositionLocalOf { AppSettings() }

val LocalUsageProviders = staticCompositionLocalOf<List<com.miniichatNext.carter.data.model.ProviderConfig>> { emptyList() }

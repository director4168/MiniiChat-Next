package com.miniichatNext.carter.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.miniichatNext.carter.data.store.AppSettings

val LocalAppPreferences = staticCompositionLocalOf { AppSettings() }

val LocalUsageProviders = staticCompositionLocalOf<List<com.miniichatNext.carter.data.model.ProviderConfig>> { emptyList() }

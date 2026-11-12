/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.util.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile")

@kotlinx.serialization.Serializable
data class UserProfile(
    val displayName: String = "You",
    val avatar: Avatar = Avatar.Emoji("🙂")
)

class UserProfileStore(private val context: Context) {
    private val key = stringPreferencesKey("user_profile_json")
    private val json get() = AppJson.json

    val profileFlow: Flow<UserProfile> = context.userProfileDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) UserProfile()
        else runCatching { json.decodeFromString(UserProfile.serializer(), raw) }
            .getOrDefault(UserProfile())
    }

    suspend fun isConfigured(): Boolean = context.userProfileDataStore.data.first()[key]?.isNotBlank() == true

    suspend fun snapshot(): UserProfile = profileFlow.first()

    suspend fun snapshotForCleanup(): UserProfile {
        val raw = context.userProfileDataStore.data.first()[key]
        return if (raw.isNullOrBlank()) UserProfile() else json.decodeFromString(UserProfile.serializer(), raw)
    }

    suspend fun save(profile: UserProfile) {
        context.userProfileDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(UserProfile.serializer(), profile)
        }
    }

    suspend fun update(transform: (UserProfile) -> UserProfile) {
        context.userProfileDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString(UserProfile.serializer(), it) } ?: UserProfile()
            prefs[key] = json.encodeToString(UserProfile.serializer(), transform(current))
        }
    }
}

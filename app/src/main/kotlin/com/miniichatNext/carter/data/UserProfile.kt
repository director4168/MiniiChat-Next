package com.miniichatNext.carter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val profileFlow: Flow<UserProfile> = context.userProfileDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) UserProfile()
        else runCatching { json.decodeFromString(UserProfile.serializer(), raw) }
            .getOrDefault(UserProfile())
    }

    suspend fun snapshot(): UserProfile = profileFlow.first()

    suspend fun save(profile: UserProfile) {
        context.userProfileDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(UserProfile.serializer(), profile)
        }
    }

    suspend fun update(transform: (UserProfile) -> UserProfile) {
        save(transform(snapshot()))
    }
}

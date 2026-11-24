/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.miniichatNext.carter.util.AppJson
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

internal class SqlPreferences(context: Context, private val namespace: String) {
    private val db = ConversationDatabase.get(context)

    val data: Flow<Preferences> = db.preferenceChanges.map {
        withContext(Dispatchers.IO) { db.readPreferences(namespace) }
    }.distinctUntilChanged().map { it.asPreferences() }

    suspend fun edit(transform: (MutablePreferences) -> Unit) = withContext(Dispatchers.IO) {
        withContext(NonCancellable) {
            val changed = db.transaction {
                val before = db.readPreferences(namespace)
                val after = before.asPreferences().apply(transform).encodePreferences()
                if (before != after) db.writePreferences(namespace, before, after)
                before != after
            }
            if (changed) db.notifyPreferencesChanged()
        }
    }
}

internal data class SqlPreference(val type: String, val value: String)

internal fun Preferences.encodePreferences(): Map<String, SqlPreference> = asMap().entries.associate { (key, value) ->
    key.name to when (value) {
        is String -> SqlPreference("string", value)
        is Int -> SqlPreference("int", value.toString())
        is Long -> SqlPreference("long", value.toString())
        is Boolean -> SqlPreference("boolean", value.toString())
        is Float -> SqlPreference("float", value.toRawBits().toString())
        is Double -> SqlPreference("double", value.toRawBits().toString())
        is ByteArray -> SqlPreference("bytes", Base64.getEncoder().encodeToString(value))
        is Set<*> -> SqlPreference("set", AppJson.json.encodeToString(ListSerializer(String.serializer()), value.map { it as String }.sorted()))
        else -> error("Unsupported preference type")
    }
}

internal fun Map<String, SqlPreference>.asPreferences(): MutablePreferences = mutablePreferencesOf().apply {
    for ((name, entry) in this@asPreferences) when (entry.type) {
        "string" -> this[stringPreferencesKey(name)] = entry.value
        "int" -> this[intPreferencesKey(name)] = entry.value.toInt()
        "long" -> this[longPreferencesKey(name)] = entry.value.toLong()
        "boolean" -> this[booleanPreferencesKey(name)] = entry.value.toBooleanStrict()
        "float" -> this[floatPreferencesKey(name)] = Float.fromBits(entry.value.toInt())
        "double" -> this[doublePreferencesKey(name)] = Double.fromBits(entry.value.toLong())
        "bytes" -> this[byteArrayPreferencesKey(name)] = Base64.getDecoder().decode(entry.value)
        "set" -> this[stringSetPreferencesKey(name)] = AppJson.json.decodeFromString(ListSerializer(String.serializer()), entry.value).toSet()
        else -> error("Unknown SQLite preference type")
    }
}

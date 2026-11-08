package com.miniichatNext.carter.data.backup

import com.miniichatNext.carter.util.AppJson
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 快速备份的密钥按约定放在索引中，只隐藏字段明文，不提供密码备份的保密性。 */
internal object BackupCredentials {
    private const val PREFIX = "backup-key-v1:"

    fun newKey(): String = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    fun validateKey(encoded: String) {
        val key = Base64.getDecoder().decode(encoded)
        try { require(key.size == 32) { "Invalid credential recovery key" } } finally { key.fill(0) }
    }

    private fun transform(value: String, encodedKey: String, encrypt: Boolean): String {
        if (value.isEmpty()) return value
        val key = Base64.getDecoder().decode(encodedKey)
        require(key.size == 32) { "Invalid credential recovery key" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secret = SecretKeySpec(key, "AES")
            return if (encrypt) {
                val iv = ByteArray(12).also(SecureRandom()::nextBytes)
                cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
                cipher.updateAAD(PREFIX.toByteArray(Charsets.US_ASCII))
                PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            } else {
                require(value.startsWith(PREFIX)) { "Expected encrypted credential field" }
                val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
                require(bytes.size >= 28) { "Invalid encrypted credential" }
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.updateAAD(PREFIX.toByteArray(Charsets.US_ASCII))
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
            }
        } finally { key.fill(0) }
    }

    fun transform(data: BackupSnapshot, key: String, encrypt: Boolean): BackupSnapshot {
        validateKey(key)
        fun field(value: String) = transform(value, key, encrypt)
        fun headers(values: Map<String, String>) = values.mapValues { field(it.value) }
        val mcp = AppJson.json.parseToJsonElement(data.mcp) as JsonObject
        val servers = mcp["servers"] as? JsonArray ?: error("Invalid MCP backup")
        val protectedMcp = JsonObject(mcp + ("servers" to JsonArray(servers.map { element ->
            val server = element as JsonObject
            val currentHeaders = server["headers"] as? JsonObject
            if (currentHeaders == null) server else JsonObject(server + ("headers" to JsonObject(currentHeaders.mapValues {
                JsonPrimitive(field((it.value as JsonPrimitive).content))
            })))
        })))
        return data.copy(
            providers = data.providers.map { provider ->
                provider.copy(apiKey = field(provider.apiKey), customHeaders = headers(provider.customHeaders),
                    models = provider.models.map { model ->
                        model.copy(apiKey = model.apiKey?.let(::field), customHeaders = headers(model.customHeaders))
                    })
            },
            mcp = protectedMcp.toString(),
        )
    }
}

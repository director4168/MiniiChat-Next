package com.miniichatNext.carter.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// 存储格式：enc1:<base64(iv)>:<base64(ciphertext)>
object ApiKeyCrypto {
    private const val PREFIX = "enc1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "minichat_next_api_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) {
            existing
        } else {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            gen.generateKey()
        }
    }.onFailure { e ->
        com.miniichatNext.carter.Debug.DebugLog.w(
            "ApiKeyCrypto", "keystore unavailable: ${e.message}"
        )
    }.getOrNull()

    fun encrypt(plain: String?): String {
        val value = plain ?: return ""
        if (value.isEmpty() || value.startsWith(PREFIX)) return value
        val key = secretKey() ?: return value
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            PREFIX +
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipherText, Base64.NO_WRAP)
        }.getOrElse { e ->
            com.miniichatNext.carter.Debug.DebugLog.w(
                "ApiKeyCrypto", "encrypt failed, storing plaintext: ${e.message}"
            )
            value
        }
    }

    fun decrypt(stored: String?): String {
        val value = stored ?: return ""
        if (value.isEmpty() || !value.startsWith(PREFIX)) return value
        val key = secretKey() ?: return ""
        return runCatching {
            val payload = value.removePrefix(PREFIX)
            val parts = payload.split(":")
            if (parts.size != 2) error("malformed payload")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrElse { e ->
            com.miniichatNext.carter.Debug.DebugLog.e(
                "ApiKeyCrypto", "decrypt failed (key needs re-entry): ${e.message}", e
            )
            ""
        }
    }
}

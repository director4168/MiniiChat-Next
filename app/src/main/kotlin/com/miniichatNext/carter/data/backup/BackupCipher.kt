package com.miniichatNext.carter.data.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 密码只用于本次备份，随机盐与IV随密文保存，不依赖Android Keystore。 */
internal object BackupCipher {
    private val magic = byteArrayOf(77, 67, 78, 66, 75, 50)
    private const val ITERATIONS = 210_000
    private const val HEADER_SIZE = 6 + 4 + 16 + 12

    fun encrypted(bytes: ByteArray): Boolean = bytes.size >= magic.size && bytes.take(magic.size).toByteArray().contentEquals(magic)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Backup password is required" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(ITERATIONS).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        require(this.encrypted(encrypted) && encrypted.size >= HEADER_SIZE + 16) { "Invalid encrypted backup" }
        val buffer = ByteBuffer.wrap(encrypted)
        buffer.position(magic.size)
        val iterations = buffer.int
        require(iterations in 100_000..1_000_000) { "Invalid KDF parameters" }
        val salt = ByteArray(16).also(buffer::get)
        val iv = ByteArray(12).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(encrypted.copyOfRange(0, HEADER_SIZE))
        return cipher.doFinal(encrypted, HEADER_SIZE, encrypted.size - HEADER_SIZE)
    }
    fun encryptFile(source: java.io.File, target: java.io.File, password: CharArray) {
        require(password.isNotEmpty()) { "Backup password is required" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(ITERATIONS).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { output ->
            output.write(header)
            source.inputStream().buffered().use { input -> transfer(input, output, cipher) }
        }
    }

    fun decryptFile(source: java.io.File, target: java.io.File, password: CharArray) {
        require(password.isNotEmpty()) { "Backup password is required" }
        source.inputStream().buffered().use { input ->
            val header = ByteArray(HEADER_SIZE)
            java.io.DataInputStream(input).readFully(header)
            require(encrypted(header)) { "Invalid encrypted backup" }
            val buffer = ByteBuffer.wrap(header).apply { position(magic.size) }
            val iterations = buffer.int
            require(iterations in 100_000..1_000_000) { "Invalid KDF parameters" }
            val salt = ByteArray(16).also(buffer::get)
            val iv = ByteArray(12).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { output -> transfer(input, output, cipher) }
        }
    }

    private fun transfer(input: java.io.InputStream, output: java.io.OutputStream, cipher: Cipher) {
        val buffer = ByteArray(65536)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            cipher.update(buffer, 0, count)?.let { bytes ->
                written += bytes.size
                require(written <= 4L * 1024 * 1024 * 1024 + 1024) { "Backup exceeds size limit" }
                output.write(bytes)
            }
        }
        val final = cipher.doFinal()
        require(written + final.size <= 4L * 1024 * 1024 * 1024 + 1024) { "Backup exceeds size limit" }
        output.write(final)
    }

}

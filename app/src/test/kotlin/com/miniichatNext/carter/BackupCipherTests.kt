package com.miniichatNext.carter

import com.miniichatNext.carter.data.backup.BackupCipher
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTests {
    @Test fun portableRoundTripAndFreshRandomness() {
        val text = "credential-token-private".toByteArray()
        val first = BackupCipher.encrypt(text, "password".toCharArray())
        val second = BackupCipher.encrypt(text, "password".toCharArray())
        assertFalse(first.contentEquals(second))
        assertTrue(BackupCipher.encrypted(first))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("credential-token-private"))
        assertArrayEquals(text, BackupCipher.decrypt(first, "password".toCharArray()))
    }

    @Test fun rejectsWrongPassword() {
        val data = BackupCipher.encrypt("test".toByteArray(), "password".toCharArray())
        assertThrows(AEADBadTagException::class.java) { BackupCipher.decrypt(data, "wrong".toCharArray()) }
    }

    @Test fun rejectsModifiedCiphertext() {
        val data = BackupCipher.encrypt("test".toByteArray(), "password".toCharArray())
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { BackupCipher.decrypt(data, "password".toCharArray()) }
    }

    @Test fun rejectsUnboundedKdfAndEmptyPassword() {
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.encrypt(byteArrayOf(1), charArrayOf()) }
        val data = BackupCipher.encrypt("test".toByteArray(), "password".toCharArray())
        java.nio.ByteBuffer.wrap(data).putInt(6, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(data, "password".toCharArray()) }
    }
}

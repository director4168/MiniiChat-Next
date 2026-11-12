/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.data.backup.*
import com.miniichatNext.carter.data.model.*
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.util.AppJson
import java.io.File
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class BackupModeTests {
    private fun data() = BackupSnapshot(
        settings=AppSettings(),providers=listOf(ProviderConfig("p","Name","https://host","secret-key")),
        assistants=emptyList(),conversations=emptyList(),profile=UserProfile(),mcp="""{"servers":[],"tools":[],"perms":[]}""",
    )

    @Test fun credentialKeyIsFreshAndWrongKeyIsRejected() {
        val first=BackupCredentials.newKey()
        val second=BackupCredentials.newKey()
        assertNotEquals(first,second)
        val protected=BackupCredentials.transform(data(),first,true)
        assertNotEquals("secret-key",protected.providers.single().apiKey)
        assertThrows(AEADBadTagException::class.java) { BackupCredentials.transform(protected,second,false) }
        assertEquals("secret-key",BackupCredentials.transform(protected,first,false).providers.single().apiKey)
    }

    @Test fun rejectsPlaintextCredentialInNewFormat() {
        assertThrows(IllegalArgumentException::class.java) { BackupCredentials.transform(data(),BackupCredentials.newKey(),false) }
    }

    @Test fun streamedCipherRoundTripsMultipleChunks() {
        val folder=kotlin.io.path.createTempDirectory().toFile()
        val plain=File(folder,"plain").apply { writeBytes(ByteArray(200000) { (it%251).toByte() }) }
        val encrypted=File(folder,"encrypted")
        val restored=File(folder,"restored")
        BackupCipher.encryptFile(plain,encrypted,"password".toCharArray())
        assertFalse(plain.readBytes().contentEquals(encrypted.readBytes()))
        BackupCipher.decryptFile(encrypted,restored,"password".toCharArray())
        assertArrayEquals(plain.readBytes(),restored.readBytes())
    }

    @Test fun inconsistentEncryptionMarkerIsRejected() {
        val folder=kotlin.io.path.createTempDirectory().toFile()
        File(folder,"data/snapshot.json").apply { parentFile?.mkdirs();writeText("{}") }
        val zip=File.createTempFile("bad-mode", ".zip")
        BackupArchive.write(folder,zip,"test",emptyList(),BackupMode.ENCRYPTED)
        assertThrows(IllegalArgumentException::class.java) {
            zip.inputStream().use { BackupArchive.read(it,kotlin.io.path.createTempDirectory().toFile()) }
        }
    }

    @Test fun quickBackupRequiresRecoveryKeyInIndex() {
        val folder=kotlin.io.path.createTempDirectory().toFile()
        File(folder,"data/snapshot.json").apply { parentFile?.mkdirs();writeText("{}") }
        val zip=File.createTempFile("no-key", ".zip")
        BackupArchive.write(folder,zip,"test",emptyList(),BackupMode.QUICK)
        assertThrows(IllegalArgumentException::class.java) {
            zip.inputStream().use { BackupArchive.read(it,kotlin.io.path.createTempDirectory().toFile()) }
        }
    }
}

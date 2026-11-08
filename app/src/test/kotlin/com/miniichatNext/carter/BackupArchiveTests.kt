package com.miniichatNext.carter

import com.miniichatNext.carter.data.backup.BackupArchive
import com.miniichatNext.carter.data.backup.BackupManifest
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.util.AppJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveTests {
    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }

    @Test fun noExtraTopLevelEntriesAllowed() {
        val stage = kotlin.io.path.createTempDirectory().toFile()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(ByteArrayInputStream(archive("settings.json" to "{}")), stage)
        }
    }

    @Test fun missingManifestIsRejected() {
        val stage = kotlin.io.path.createTempDirectory().toFile()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(ByteArrayInputStream(archive("data/snapshot.json" to "{}")), stage)
        }
    }

    @Test fun unindexedPayloadIsRejected() {
        val stage = kotlin.io.path.createTempDirectory().toFile()
        val manifest = AppJson.json.encodeToString(BackupManifest.serializer(), BackupManifest(appVersion = "test", createdAt = 1, files = emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(ByteArrayInputStream(archive("manifest.json" to manifest, "data/snapshot.json" to "{}")), stage)
        }
    }

    @Test fun hashMatchesStandardMd5() {
        val file = File.createTempFile("hash", ".txt").apply { writeText("abc") }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", BackupArchive.md5(file))
    }

    @Test fun defaultsMatchReferenceWithoutToolVisibilityToggle() {
        val settings = AppSettings()
        assertTrue(settings.autoNewChatOnLaunch)
        assertEquals(15f, settings.fontSizeSp)
        assertTrue(settings.showReasoningCard)
        assertTrue(settings.showCardTokenUsage)
        assertFalse(settings.sendOnEnter)
        assertFalse(settings.pasteAsAttachment)
    }
}

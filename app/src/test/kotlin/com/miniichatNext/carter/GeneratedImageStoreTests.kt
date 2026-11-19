/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.data.model.GeneratedImageStore
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageStoreTests {
    @Test fun materializesMarkdownDataImageIntoPrivateAttachment() {
        val root = Files.createTempDirectory("generated-images").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val uri = Base64.getEncoder().encodeToString(bytes)
            val input = "Before ![chart](data:image/png;base64,$uri) after"
            val result = GeneratedImageStore.materialize(input, root)
            assertEquals(1, result.attachments.size)
            val image = result.attachments.single()
            assertEquals("image", image.type)
            assertEquals("image/png", image.mimeType)
            assertTrue(image.uri.startsWith(root.resolve("generated-images").toURI().toString()))
            assertTrue(java.io.File(java.net.URI(image.uri)).readBytes().contentEquals(bytes))
            assertFalse(result.content.contains("data:image/"))
            assertTrue(result.content.contains(image.uri))
        } finally { root.deleteRecursively() }
    }

    @Test fun leavesUnsupportedOrOversizedDataUriUnchanged() {
        val root = Files.createTempDirectory("generated-images-limit").toFile()
        try {
            val input = "![image](data:image/svg+xml;base64,PHN2Zy8+)"
            val result = GeneratedImageStore.materialize(input, root)
            assertEquals(input, result.content)
            assertTrue(result.attachments.isEmpty())
        } finally { root.deleteRecursively() }
    }
}

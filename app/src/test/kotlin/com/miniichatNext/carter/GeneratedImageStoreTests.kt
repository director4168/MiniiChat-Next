/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.data.model.GeneratedImageStore
import com.miniichatNext.carter.data.model.ImagePreviewStorage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun materializesJpgDataUriByMagic() {
        val root = Files.createTempDirectory("generated-images-jpg").toFile()
        try {
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
            val uri = Base64.getEncoder().encodeToString(bytes)
            val input = "![photo](data:image/jpg;base64,$uri)"
            val result = GeneratedImageStore.materialize(input, root)
            assertEquals(1, result.attachments.size)
            val image = result.attachments.single()
            assertEquals("image/jpeg", image.mimeType)
            assertTrue(image.uri.endsWith(".jpg"))
            assertTrue(java.io.File(java.net.URI(image.uri)).readBytes().contentEquals(bytes))
        } finally { root.deleteRecursively() }
    }

    @Test fun skipsImagesInsideFencedCode() {
        val root = Files.createTempDirectory("generated-images-code").toFile()
        try {
            val uri = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
            val input = "```\n![sample](data:image/png;base64,$uri)\n```"
            val result = GeneratedImageStore.materialize(input, root)
            assertEquals(input, result.content)
            assertTrue(result.attachments.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun rewritesFileImageMarkdownToSafeInternalUrl() {
        val input = "before ![chart](file:///data/user/0/app/files/generated-images/a.png) after"
        assertEquals(
            "before ![chart](https://miniichat.local/generated/a.png) after",
            GeneratedImageStore.rewriteLocalMarkdownImages(input),
        )
        val code = "```\n![chart](file:///data/user/0/app/files/generated-images/a.png)\n```"
        assertEquals(code, GeneratedImageStore.rewriteLocalMarkdownImages(code))
    }

    @Test fun rewritesArbitraryAccessibleFileImage() {
        val path = "/storage/emulated/0/Download/red.png"
        val input = "![red](file:$path)"
        val rewritten = GeneratedImageStore.rewriteLocalMarkdownImages(input)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray())
        assertEquals("![red](${GeneratedImageStore.LOCAL_IMAGE_PREFIX}$encoded)", rewritten)
        val root = Files.createTempDirectory("generated-arbitrary").toFile()
        try {
            val file = File(root, "red.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val scheme = "${GeneratedImageStore.LOCAL_IMAGE_PREFIX}" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(file.absolutePath.toByteArray())
            assertEquals(file.canonicalPath, ImagePreviewStorage.load(root, scheme).canonicalPath)
        } finally { root.deleteRecursively() }
    }

    @Test fun findsLocalFileForCommonFileUriForms() {
        val root = Files.createTempDirectory("generated-image-find").toFile()
        try {
            val directory = File(root, "generated-images").apply { mkdirs() }
            val image = File(directory, "sample.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertNotNull(GeneratedImageStore.findLocal(root, image.toURI().toASCIIString()))
            assertNotNull(GeneratedImageStore.findLocal(root, "file://" + image.absolutePath))
            assertEquals(image.canonicalPath, ImagePreviewStorage.load(root, GeneratedImageStore.PUBLIC_IMAGE_PREFIX + "sample.png").canonicalPath)
        } finally { root.deleteRecursively() }
    }

    @Test fun extractsAndRewritesLocalImageReferences() {
        val source = "before ![chart](file:///data/user/0/app/files/generated-images/a.png) after"
        val refs = GeneratedImageStore.references(source)
        assertEquals(listOf("file:///data/user/0/app/files/generated-images/a.png"), refs)
        val rewritten = GeneratedImageStore.mapReferences(source) { "backup://generated/asset.png" }
        assertEquals("before ![chart](backup://generated/asset.png) after", rewritten)
    }

    @Test fun leavesUnsupportedDataUriUnchanged() {
        val root = Files.createTempDirectory("generated-images-limit").toFile()
        try {
            val input = "![image](data:image/avif;base64,AAAA)"
            val result = GeneratedImageStore.materialize(input, root)
            assertEquals(input, result.content)
            assertTrue(result.attachments.isEmpty())
        } finally { root.deleteRecursively() }
    }
}

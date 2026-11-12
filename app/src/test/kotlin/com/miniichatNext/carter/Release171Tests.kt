/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.api.chatEndpoint
import com.miniichatNext.carter.api.modelsEndpoint
import com.miniichatNext.carter.data.avatar.AvatarFileCleanup
import com.miniichatNext.carter.data.avatar.AvatarFileTransfer
import com.miniichatNext.carter.data.avatar.AvatarPaths
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.MessageSegment
import com.miniichatNext.carter.data.model.ProviderPresets
import com.miniichatNext.carter.data.model.ProviderType
import com.miniichatNext.carter.ui.chat.ChatListOrder
import com.miniichatNext.carter.ui.chat.withStreamingSnapshot
import com.miniichatNext.carter.util.TemporaryCacheCleaner
import com.miniichatNext.carter.vm.StreamOverlay
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Release171Tests {
    private fun oldFile(directory: File, name: String): File = File(directory, name).apply {
        parentFile?.mkdirs()
        writeText("fixture")
        setLastModified(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000)
    }

    @Test fun automaticCleanupPreservesCanonicalAndLegacyResourcesWithoutReferences() {
        val files = Files.createTempDirectory("avatar-files").toFile()
        try {
            val directory = File(files, "avatars").apply { mkdirs() }
            val user = oldFile(files, AvatarPaths.user(files).relativeTo(files).path)
            val assistant = oldFile(files, AvatarPaths.assistant(files, "assistant").relativeTo(files).path)
            val background = oldFile(files, AvatarPaths.background(files, "assistant").relativeTo(files).path)
            val legacy = oldFile(directory, "legacy-uuid.jpg")
            val other = oldFile(directory, "notes.txt")
            val temporary = oldFile(directory, "avatar_123456.jpg")
            assertEquals(1, AvatarFileCleanup.pruneTemporary(directory, emptyList(), 24L * 60 * 60 * 1000))
            assertFalse(temporary.exists())
            listOf(user, assistant, background, legacy, other).forEach { assertTrue(it.exists()) }
        } finally { files.deleteRecursively() }
    }

    @Test fun dedicatedCropDirectoryDoesNotPruneOldRootLevelCropImages() {
        val directory = Files.createTempDirectory("avatar-crop-dir").toFile()
        try {
            val legacy = oldFile(directory, "avatar_123.jpg")
            val oldCrop = oldFile(directory, "crop_456.jpg")
            val crops = File(directory, ".crop").apply { mkdirs() }
            val temporary = oldFile(crops, "avatar_789.jpg")
            assertEquals(1, AvatarFileCleanup.pruneTemporary(crops, emptyList(), 0))
            assertTrue(legacy.exists())
            assertTrue(oldCrop.exists())
            assertFalse(temporary.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cleanupPreservesReferencedCropWithFileUriAndNormalizedPath() {
        val directory = Files.createTempDirectory("avatar-refs").toFile()
        try {
            val first = oldFile(directory, "avatar_123.jpg")
            val second = oldFile(directory, "crop_456.jpg")
            val third = File(directory, "avatar_789.jpg").apply { writeText("recent") }
            val references = listOf(first.toURI().toString(), File(directory, "nested/../${second.name}").path)
            assertEquals(0, AvatarFileCleanup.pruneTemporary(directory, references, 24L * 60 * 60 * 1000))
            assertTrue(first.exists())
            assertTrue(second.exists())
            assertTrue(third.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun temporaryDeletionRejectsPermanentFilesAndExternalPaths() {
        val parent = Files.createTempDirectory("avatar-boundary").toFile()
        try {
            val directory = File(parent, "avatars").apply { mkdirs() }
            val external = oldFile(parent, "avatar_123.jpg")
            val permanent = oldFile(directory, "Avatars_User.jpg")
            val temporary = oldFile(directory, "crop_456.jpg")
            assertFalse(AvatarFileCleanup.deleteTemporary(directory, external.path))
            assertFalse(AvatarFileCleanup.deleteTemporary(directory, permanent.path))
            assertTrue(AvatarFileCleanup.deleteTemporary(directory, temporary.path))
            assertTrue(external.exists())
            assertTrue(permanent.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun cleanupLeavesSymlinkTargetsUntouched() {
        val parent = Files.createTempDirectory("avatar-link").toFile()
        try {
            val directory = File(parent, "avatars").apply { mkdirs() }
            val external = oldFile(parent, "external.jpg")
            val link = File(directory, "avatar_123.jpg")
            Files.createSymbolicLink(link.toPath(), external.toPath())
            assertFalse(AvatarFileCleanup.deleteTemporary(directory, link.path))
            assertEquals(0, AvatarFileCleanup.pruneTemporary(directory, emptyList(), 0))
            assertTrue(external.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun invalidReferenceSkipsTemporaryCleanup() {
        val directory = Files.createTempDirectory("avatar-invalid").toFile()
        try {
            val temporary = oldFile(directory, "avatar_123.jpg")
            assertEquals(0, AvatarFileCleanup.pruneTemporary(directory, listOf("content://media/item"), 0))
            assertTrue(temporary.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cacheCleanupPreservesFilesAndDownloadedUpdate() {
        val parent = Files.createTempDirectory("cache-files").toFile()
        try {
            val files = File(parent, "files").apply { mkdirs() }
            val cache = File(parent, "cache").apply { mkdirs() }
            val avatar = oldFile(files, "avatars/Avatars_User.jpg")
            val attachment = oldFile(files, "uploads/file.txt")
            val skill = oldFile(files, "skills/demo/SKILL.md")
            val rootfs = oldFile(files, "workspaces/default/linux/bin/sh")
            val update = oldFile(cache, "Update/update.apk")
            val temporary = oldFile(cache, "crop.jpg")
            assertEquals(1, TemporaryCacheCleaner.clearDirectory(cache, files))
            assertFalse(temporary.exists())
            listOf(avatar, attachment, skill, rootfs, update).forEach { assertTrue(it.exists()) }
        } finally { parent.deleteRecursively() }
    }

    @Test fun cacheCleanupSkipsFileStorageAndNestedSymlinks() {
        val parent = Files.createTempDirectory("cache-links").toFile()
        try {
            val files = File(parent, "files").apply { mkdirs() }
            val cache = File(parent, "cache").apply { mkdirs() }
            val avatar = oldFile(files, "avatars/Avatars_User.jpg")
            assertEquals(0, TemporaryCacheCleaner.clearDirectory(files, files))
            val linked = File(cache, "linked")
            Files.createSymbolicLink(linked.toPath(), files.toPath())
            val nested = File(cache, "nested").apply { mkdirs() }
            Files.createSymbolicLink(File(nested, "avatars").toPath(), File(files, "avatars").toPath())
            assertEquals(0, TemporaryCacheCleaner.clearDirectory(cache, files))
            assertTrue(avatar.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun reenteredStreamingRowUsesCurrentSnapshotInsteadOfOlderStorage() {
        val stored = Message("assistant", "assistant", "old", reasoningContent = "old thinking")
        val parts = listOf(MessageSegment.Text("old and new text"))
        val live = StreamOverlay("assistant", "old and new text", "new thinking", parts, generationTimeMs = 100)
        val displayed = stored.withStreamingSnapshot(live)
        assertEquals("old and new text", displayed.content)
        assertEquals("new thinking", displayed.reasoningContent)
        assertEquals(parts, displayed.orderedParts)
        assertEquals(100L, displayed.generationTimeMs)
    }

    @Test fun olderOverlayDoesNotRollStoredMessageBack() {
        val stored = Message("assistant", "assistant", "long current content", reasoningContent = "current reasoning")
        assertSame(stored, stored.withStreamingSnapshot(StreamOverlay("assistant", "long", "current reasoning", emptyList())))
        assertSame(stored, stored.withStreamingSnapshot(StreamOverlay("assistant", stored.content, "current", emptyList())))
    }

    @Test fun cleanupRejectsSymlinkedAvatarDirectoryAndNestedCropFiles() {
        val parent = Files.createTempDirectory("avatar-root-link").toFile()
        try {
            val directory = File(parent, "real").apply { mkdirs() }
            val rootLink = File(parent, "avatars")
            Files.createSymbolicLink(rootLink.toPath(), directory.toPath())
            val file = oldFile(directory, "avatar_123.jpg")
            val nested = oldFile(directory, "nested/crop_456.jpg")
            assertFalse(AvatarFileCleanup.deleteTemporary(rootLink, File(rootLink, file.name).path))
            assertEquals(0, AvatarFileCleanup.pruneTemporary(rootLink, emptyList(), 0))
            assertFalse(AvatarFileCleanup.deleteTemporary(directory, nested.path))
            assertTrue(file.exists())
            assertTrue(nested.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun blankOverlayReasoningKeepsSavedReasoningAndNewBody() {
        val stored = Message("assistant", "assistant", "old", reasoningContent = "saved thinking")
        val live = StreamOverlay("assistant", "old new", "", emptyList())
        val result = stored.withStreamingSnapshot(live)
        assertEquals("old new", result.content)
        assertEquals("saved thinking", result.reasoningContent)
    }

    @Test fun streamingSnapshotForAnotherMessageIsIgnored() {
        val stored = Message("current", "assistant", "current content")
        val other = StreamOverlay("other", "other content", "", emptyList())
        assertSame(stored, stored.withStreamingSnapshot(other))
        assertSame(stored, stored.withStreamingSnapshot(null))
    }

    @Test fun sameAvatarThroughDirectoryAliasIsNeverDeletedByCopy() {
        val parent = Files.createTempDirectory("avatar-alias").toFile()
        try {
            val real = File(parent, "real").apply { mkdirs() }
            val alias = File(parent, "alias")
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            val source = oldFile(real, "Avatars_User.jpg")
            val target = File(alias, source.name)
            assertEquals(target, AvatarFileTransfer.copy(source, target))
            assertEquals("fixture", source.readText())
            assertEquals("fixture", target.readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun failedAvatarWriteKeepsOriginalBytesAndRemovesStaging() {
        val parent = Files.createTempDirectory("avatar-write-fail").toFile()
        try {
            val target = oldFile(parent, "Avatars_User.jpg")
            var failed = false
            try {
                AvatarFileTransfer.write(target) { staged ->
                    staged.writeText("partial")
                    error("fixture write failure")
                }
            } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertEquals("fixture", target.readText())
            assertEquals(listOf(target.name), parent.listFiles()!!.map { it.name })
        } finally { parent.deleteRecursively() }
    }

    @Test fun emptyAvatarWriteDoesNotReplaceExistingImage() {
        val parent = Files.createTempDirectory("avatar-write-empty").toFile()
        try {
            val target = oldFile(parent, "Avatars_User.jpg")
            var failed = false
            try { AvatarFileTransfer.write(target) { } } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertEquals("fixture", target.readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun completedAvatarCopyReplacesTargetAfterFullWrite() {
        val parent = Files.createTempDirectory("avatar-copy").toFile()
        try {
            val source = File(parent, "source.jpg").apply { writeText("new picture") }
            val target = oldFile(parent, "Avatars_User.jpg")
            AvatarFileTransfer.copy(source, target)
            assertEquals("new picture", target.readText())
            assertEquals("new picture", source.readText())
            assertEquals(2, parent.listFiles()!!.size)
        } finally { parent.deleteRecursively() }
    }

    @Test fun xiaoyePresetIsImmediatelyBeforeXaiAndKeepsOpenAiDefaults() {
        val presets = ProviderPresets.all
        val index = presets.indexOfFirst { it.name == "Xiaoye" }
        assertTrue(index >= 0)
        assertEquals("xAI", presets[index + 1].name)
        val preset = presets[index]
        assertEquals("https://api.lwxiaoye.top/v1", preset.baseUrl)
        assertEquals("/chat/completions", preset.path)
        assertEquals(ProviderType.OPENAI, preset.type)
    }
    @Test fun xiaoyeEndpointsKeepTheV1PrefixExactlyOnce() {
        val preset = ProviderPresets.all.single { it.name == "Xiaoye" }
        assertEquals("https://api.lwxiaoye.top/v1/chat/completions", chatEndpoint(preset.baseUrl, preset.path))
        assertEquals("https://api.lwxiaoye.top/v1/models", modelsEndpoint(preset.baseUrl))
        assertEquals("https://api.lwxiaoye.top/v1/models", modelsEndpoint(preset.baseUrl + "/"))
    }

    @Test fun reverseListIndexStillMapsToChronologicalMessages() {
        assertEquals(4, ChatListOrder.messageIndex(0, 5))
        assertEquals(0, ChatListOrder.messageIndex(4, 5))
        assertEquals(listOf(4, 3, 2, 1, 0), (0 until 5).map { ChatListOrder.messageIndex(it, 5) })
    }

    @Test fun reverseListMappingRejectsFooterAndInvalidIndices() {
        assertEquals(null, ChatListOrder.messageIndex(-1, 5))
        assertEquals(null, ChatListOrder.messageIndex(5, 5))
        assertEquals(null, ChatListOrder.messageIndex(0, 0))
    }

    @Test fun reverseMessageJumperKeepsVisualUpAndDownMeaning() {
        assertEquals(4, ChatListOrder.jumpTarget(0, 2, 5))
        assertEquals(3, ChatListOrder.jumpTarget(1, 2, 5))
        assertEquals(1, ChatListOrder.jumpTarget(2, 2, 5))
        assertEquals(0, ChatListOrder.jumpTarget(3, 2, 5))
    }

    @Test fun reverseMessageJumperClampsAtBothEnds() {
        assertEquals(4, ChatListOrder.jumpTarget(1, 4, 5))
        assertEquals(0, ChatListOrder.jumpTarget(2, 0, 5))
        assertEquals(4, ChatListOrder.jumpTarget(1, 100, 5))
        assertEquals(0, ChatListOrder.jumpTarget(2, -10, 5))
    }

    @Test fun reverseMessageJumperHandlesEmptyAndSingleMessageLists() {
        (0..3).forEach { action ->
            assertEquals(null, ChatListOrder.jumpTarget(action, 0, 0))
            assertEquals(0, ChatListOrder.jumpTarget(action, 0, 1))
        }
        assertEquals(null, ChatListOrder.jumpTarget(-1, 0, 5))
        assertEquals(null, ChatListOrder.jumpTarget(4, 0, 5))
    }

    @Test fun reverseMessageJumperAlignsLongTargetByDirection() {
        (0..2).forEach { action -> assertEquals(300, ChatListOrder.jumpOffset(action, 800, 500)) }
        (3..3).forEach { action -> assertEquals(0, ChatListOrder.jumpOffset(action, 800, 500)) }
    }

    @Test fun reverseMessageJumperUsesNegativeOffsetForShortMessageTop() {
        (0..2).forEach { action -> assertEquals(-300, ChatListOrder.jumpOffset(action, 200, 500)) }
        (3..3).forEach { action -> assertEquals(0, ChatListOrder.jumpOffset(action, 200, 500)) }
    }

}

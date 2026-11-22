/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.data.tools.planTextEdit
import com.miniichatNext.carter.data.tools.resolveToolPath
import com.miniichatNext.carter.data.workspace.WorkspaceFileSystem
import com.miniichatNext.carter.data.tools.resolveRenameTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TextEditMatcherTests {
    @Test fun changedMiddleDoesNotMatchOldFirstAndLastAnchors() {
        val plan = planTextEdit("start\nnew middle\nend\n", "start\nold middle\nend\n", "replacement", false)
        assertTrue(plan.matches.isEmpty())
        assertTrue(plan.error!!.contains("not found"))
    }

    @Test fun trimMatchingAppliesIndentShiftToReplacement() {
        val plan = planTextEdit("  fun run() {\n      old()\n  }\n", "fun run() {\n    old()\n}\n", "fun run() {\n    next()\n}\n", false)
        assertEquals(1, plan.matches.size)
        assertEquals("  fun run() {\n      next()\n  }\n", plan.matches.single().replacement)
    }

    @Test fun CRLFFileKeepsLineEndingsOnWhitespaceMatch() {
        val plan = planTextEdit("  one\r\n  two\r\n", "one\ntwo", "uno\ndos", false)
        assertEquals("  uno\r\n  dos", plan.matches.single().replacement)
    }

    @Test fun repeatedExactMatchRequiresReplaceAll() {
        val plan = planTextEdit("x x x", "x", "y", false)
        assertTrue(plan.matches.isEmpty())
        assertTrue(plan.error!!.contains("matches 3"))
        assertEquals(3, planTextEdit("x x x", "x", "y", true).matches.size)
    }

    @Test fun outOfRootPathRaisesInsteadOfRedirectingToDownload() {
        val root = Files.createTempDirectory("allowed-path").toFile()
        val outside = Files.createTempDirectory("outside-path").toFile()
        try {
            val error = runCatching { resolveToolPath(outside.resolve("data.txt").path, listOf(root)) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("outside allowed roots"))
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun workspaceFilesRejectTraversalAndSymlinkEscape() {
        val root = Files.createTempDirectory("workspace-root").toFile()
        val outside = Files.createTempDirectory("workspace-outside").toFile()
        try {
            outside.resolve("secret.txt").writeText("secret")
            Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
            val fs = WorkspaceFileSystem()
            assertTrue(runCatching { fs.list(root, "../outside") }.isFailure)
            assertTrue(runCatching { fs.list(root, "link") }.isFailure)
            assertTrue(runCatching { fs.writeText(root, "link/secret.txt", "changed") }.isFailure)
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun workspaceGlobUsesRequestedSubdirectory() {
        val root = Files.createTempDirectory("workspace-glob").toFile()
        try {
            root.resolve("one").mkdirs()
            root.resolve("one/a.kt").writeText("a")
            root.resolve("two").mkdirs()
            root.resolve("two/b.kt").writeText("b")
            val fs = WorkspaceFileSystem()
            assertEquals(listOf("a.kt"), fs.glob(root, "*.kt", "one").map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun renameCannotTraverseOrReplaceRoot() {
        val root = Files.createTempDirectory("rename-root").toFile()
        try {
            val source = root.resolve("a.txt").apply { writeText("a") }
            assertTrue(runCatching { resolveRenameTarget(source, "../outside", listOf(root)) }.isFailure)
            assertTrue(runCatching { resolveRenameTarget(root, "renamed", listOf(root)) }.isFailure)
            root.resolve("taken.txt").writeText("b")
            assertTrue(runCatching { resolveRenameTarget(source, "taken.txt", listOf(root)) }.isFailure)
            assertTrue(resolveRenameTarget(source, "new.txt", listOf(root)).path.endsWith("new.txt"))
        } finally { root.deleteRecursively() }
    }

}

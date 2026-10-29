package com.miniichatNext.carter

import com.miniichatNext.carter.data.workspace.commitStagedRootfs
import com.miniichatNext.carter.data.workspace.recoverRootfsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RootfsLifecycleTests {
    @Test fun failedValidationRestoresPreviousRootfs() {
        val base = Files.createTempDirectory("rootfs-swap").toFile()
        try {
            val linux = base.resolve("linux").apply { resolve("bin").mkdirs(); resolve("bin/sh").writeText("old") }
            val stage = base.resolve("stage").apply { resolve("bin").mkdirs(); resolve("bin/sh").writeText("new") }
            val backup = base.resolve("backup")
            val failure = runCatching { commitStagedRootfs(stage, linux, backup) { error("patch failed") } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("patch failed"))
            assertEquals("old", linux.resolve("bin/sh").readText())
            assertFalse(backup.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun validRootfsReplacesOldAndRemovesBackup() {
        val base = Files.createTempDirectory("rootfs-commit").toFile()
        try {
            val linux = base.resolve("linux").apply { resolve("bin").mkdirs(); resolve("bin/sh").writeText("old") }
            val stage = base.resolve("stage").apply { resolve("bin").mkdirs(); resolve("bin/sh").writeText("new") }
            val backup = base.resolve("backup")
            commitStagedRootfs(stage, linux, backup) { candidate -> require(candidate.resolve("bin/sh").isFile) }
            assertEquals("new", linux.resolve("bin/sh").readText())
            assertFalse(backup.exists())
        } finally { base.deleteRecursively() }
    }
    @Test fun recoveryPrefersBackupWhenNewRootfsAlreadyExists() {
        val base = Files.createTempDirectory("rootfs-recover-new").toFile()
        try {
            val linux = base.resolve("linux").apply { resolve("bin").mkdirs(); resolve("bin/sh").writeText("new") }
            val backup = base.resolve("backup").apply { resolve("bin").mkdirs(); resolve("bin/sh").writeText("old") }
            recoverRootfsBackup(linux, backup)
            assertEquals("old", linux.resolve("bin/sh").readText())
            assertFalse(backup.exists())
        } finally { base.deleteRecursively() }
    }

}

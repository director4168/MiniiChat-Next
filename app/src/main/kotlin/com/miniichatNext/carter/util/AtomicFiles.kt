/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

internal fun publishFile(staged: File, target: File, overwrite: Boolean = true) {
    if (overwrite) Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    else Files.move(staged.toPath(), target.toPath())
}

internal fun writeAtomicFile(
    target: File,
    overwrite: Boolean = true,
    beforeCommit: () -> Unit = {},
    write: (File) -> Unit,
) {
    val parent = requireNotNull(target.parentFile)
    val staged = Files.createTempFile(parent.toPath(), ".pending-", ".tmp").toFile()
    try {
        write(staged)
        if (target.isFile && !Files.isSymbolicLink(target.toPath())) {
            val permissions = Files.getFileAttributeView(target.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            if (permissions != null) Files.setPosixFilePermissions(staged.toPath(), permissions.readAttributes().permissions())
        }
        beforeCommit()
        publishFile(staged, target, overwrite)
    } finally {
        staged.delete()
    }
}

internal fun deleteTreeNoFollow(target: File): Boolean {
    val path = target.toPath()
    if (!Files.exists(path, NOFOLLOW_LINKS)) return false
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exception: java.io.IOException?): FileVisitResult {
            if (exception != null) throw exception
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
    return true
}

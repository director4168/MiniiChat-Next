/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.avatar

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 路径别名指向同一文件时直接复用；替换图片前先完整写入暂存文件。 */
internal object AvatarFileTransfer {
    fun copy(source: File, target: File): File {
        require(source.isFile) { "Avatar source is not a file" }
        if (source.canonicalFile == target.canonicalFile ||
            (target.exists() && Files.isSameFile(source.toPath(), target.toPath()))
        ) return target
        return write(target) { staged -> source.copyTo(staged, overwrite = true) }
    }

    fun write(target: File, writer: (File) -> Unit): File {
        val parent = requireNotNull(target.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Avatar directory is not writable" }
        val staged = File.createTempFile("avatar_write_", ".tmp", parent)
        try {
            writer(staged)
            check(staged.isFile && staged.length() > 0) { "Empty avatar output" }
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally { staged.delete() }
    }
}

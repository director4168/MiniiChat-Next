package com.miniichatNext.carter.data.workspace

import java.io.File
import java.io.IOException

internal fun recoverRootfsBackup(linuxDir: File, backupDir: File) {
    if (!backupDir.exists()) return
    // backup存在就说明旧Rootfs已经被移出，上一轮提交可能在rename/校验中断。
    // 无论linuxDir是否已经出现，都优先恢复旧版本，避免启动到未校验的新Rootfs。
    if (linuxDir.exists()) check(linuxDir.deleteRecursively()) { "Interrupted rootfs could not be removed" }
    check(backupDir.renameTo(linuxDir)) { "Previous rootfs backup could not be restored" }
}

internal fun commitStagedRootfs(stagingDir: File, linuxDir: File, backupDir: File, validate: (File) -> Unit) {
    require(stagingDir.isDirectory) { "Rootfs staging directory is missing" }
    recoverRootfsBackup(linuxDir, backupDir)
    val hadOldRootfs = linuxDir.exists()
    if (hadOldRootfs) require(linuxDir.renameTo(backupDir)) { "Failed to preserve existing rootfs" }
    try {
        require(stagingDir.renameTo(linuxDir)) { "Failed to move rootfs into workspace" }
        validate(linuxDir)
        check(backupDir.deleteRecursively()) { "Previous rootfs backup could not be removed" }
    } catch (failure: Throwable) {
        linuxDir.deleteRecursively()
        if (hadOldRootfs && backupDir.exists() && !backupDir.renameTo(linuxDir)) {
            failure.addSuppressed(IOException("New rootfs failed validation and the previous rootfs could not be restored"))
        }
        throw failure
    }
}

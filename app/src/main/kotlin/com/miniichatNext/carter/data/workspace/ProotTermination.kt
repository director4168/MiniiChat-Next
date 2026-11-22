/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import java.io.File
import java.util.concurrent.TimeUnit

internal fun terminateProot(process: Process, pidFile: File) {
    val deadline = System.currentTimeMillis() + 1000
    var pids: List<Long>? = null
    while (process.isAlive && System.currentTimeMillis() < deadline) {
        pids = runCatching { pidFile.readText().trim().split(' ').mapNotNull(String::toLongOrNull).takeIf { it.size == 2 } }.getOrNull()
        if (pids != null) break
        Thread.sleep(25)
    }
    val (shell, proot) = pids ?: run {
        process.destroyForcibly()
        process.waitFor(2000, TimeUnit.MILLISECONDS)
        return
    }
    repeat(10) {
        File("/proc").listFiles().orEmpty().forEach { dir ->
            val pid = dir.name.toLongOrNull() ?: return@forEach
            val tracer = runCatching { File(dir, "status").useLines { lines -> lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toLongOrNull() } }.getOrNull()
            if (tracer == proot && pid != shell) runCatching { ProcessBuilder("/system/bin/kill", "-9", pid.toString()).start().waitFor(100, TimeUnit.MILLISECONDS) }
        }
        if (process.waitFor(200, TimeUnit.MILLISECONDS)) return
    }
    process.destroyForcibly()
    process.waitFor(1000, TimeUnit.MILLISECONDS)
}

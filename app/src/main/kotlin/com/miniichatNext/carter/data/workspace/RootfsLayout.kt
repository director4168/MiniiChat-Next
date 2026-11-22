/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal object RootfsLayout {
    fun resolve(root: File, guestPath: String): File? = runCatching {
        if (Files.isSymbolicLink(root.toPath())) return null
        val base = root.canonicalFile
        val pending = ArrayDeque(guestPath.split('/'))
        val segments = mutableListOf<String>()
        var links = 0
        var steps = 0
        while (pending.isNotEmpty()) {
            if (++steps > 256) return null
            when (val part = pending.removeFirst()) {
                "", "." -> continue
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> {
                    val candidate = File(base, (segments + part).joinToString("/"))
                    if (Files.isSymbolicLink(candidate.toPath())) {
                        if (++links > 40) return null
                        val target = Files.readSymbolicLink(candidate.toPath())
                        if (target.isAbsolute) segments.clear()
                        target.toString().split('/').asReversed().forEach(pending::addFirst)
                    } else segments += part
                }
            }
        }
        File(base, segments.joinToString("/"))
    }.getOrNull()

    private fun executable(root: File, name: String): File? = resolve(root, name)?.takeIf {
        Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.canExecute()
    }

    fun isUsable(root: File): Boolean = executable(root, "/bin/sh") != null && env(root) != null

    fun shell(root: File): String = if (executable(root, "/bin/bash") != null) "/bin/bash" else "/bin/sh"

    fun env(root: File): String? = listOf("/usr/bin/env", "/bin/env").firstOrNull { executable(root, it) != null }

    fun abi(file: File): String? = runCatching {
        val header = ByteArray(20)
        java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
        if (!header.copyOf(4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) || header[5].toInt() !in 1..2) return null
        when (val machine = (header[18].toInt() and 255) + ((header[19].toInt() and 255) shl 8)) {
            40 -> "armeabi-v7a".takeIf { header[4] == 1.toByte() }
            183 -> "arm64-v8a".takeIf { header[4] == 2.toByte() }
            62 -> "x86_64".takeIf { header[4] == 2.toByte() }
            else -> null
        }
    }.getOrNull()

    fun matchesAbi(root: File, expected: String): Boolean {
        val sh = executable(root, "/bin/sh") ?: return false
        val environment = env(root)?.let { resolve(root, it) } ?: return false
        return abi(sh) == expected && abi(environment) == expected
    }

    fun prootAbi(nativeDir: File): String? {
        val exec = File(nativeDir, "libproot_exec.so")
        val loader = File(nativeDir, "libproot_loader.so")
        return abi(exec)?.takeIf { it == abi(loader) }
    }
}

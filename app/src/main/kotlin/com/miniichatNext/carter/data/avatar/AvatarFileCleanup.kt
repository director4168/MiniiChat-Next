package com.miniichatNext.carter.data.avatar

import java.io.File
import java.net.URI
import java.nio.file.Files

/** 回收裁剪专用目录内的临时文件，正式头像、背景及旧版资源都保留。 */
internal object AvatarFileCleanup {
    private val temporaryName = Regex("""(?:avatar|crop)_[0-9]+\.jpg""")

    private fun localFile(path: String): File? = runCatching {
        when {
            path.startsWith("file:", ignoreCase = true) -> File(URI(path))
            "://" in path -> null
            else -> File(path)
        }
    }.getOrNull()

    private fun temporaryFile(directory: File, path: String): File? = runCatching {
        if (Files.isSymbolicLink(directory.toPath())) return null
        val file = localFile(path) ?: return null
        if (!temporaryName.matches(file.name) || Files.isSymbolicLink(file.toPath())) return null
        val root = directory.canonicalFile
        val target = file.canonicalFile
        target.takeIf { it.isFile && it.parentFile == root }
    }.getOrNull()

    fun deleteTemporary(directory: File, path: String): Boolean = runCatching {
        temporaryFile(directory, path)?.delete() == true
    }.getOrDefault(false)

    fun pruneTemporary(directory: File, keepPaths: Collection<String>, olderThanMs: Long): Int {
        if (olderThanMs < 0 || !directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return 0
        val keep = mutableSetOf<String>()
        for (path in keepPaths.filter { it.isNotBlank() }) {
            val file = localFile(path) ?: return 0
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return 0
            keep += canonical
        }
        val cutoff = System.currentTimeMillis() - olderThanMs
        return directory.listFiles()?.count { entry ->
            val file = temporaryFile(directory, entry.path)
            file != null && file.path !in keep && file.lastModified() in 1L until cutoff && file.delete()
        } ?: 0
    }
}

package com.miniichatNext.carter.data.tools

import java.io.File

internal fun resolveToolPath(rawPath: String, roots: List<File>): File {
    val trimmed = rawPath.trim()
    val target = when {
        trimmed.isEmpty() || trimmed == "/" || trimmed == "." || trimmed == "./" -> "/storage/emulated/0"
        trimmed == "/tmp" -> "/storage/emulated/0/Download"
        trimmed.startsWith("/tmp/") -> "/storage/emulated/0/Download/" + trimmed.removePrefix("/tmp/")
        !trimmed.startsWith("/") -> "/storage/emulated/0/$trimmed"
        else -> trimmed
    }
    val file = File(target).canonicalFile
    val allowed = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .any { root -> file.path == root.path || file.path.startsWith(root.path + File.separator) }
    require(allowed) { "path is outside allowed roots: ${file.path}" }
    return file
}

internal fun resolveRenameTarget(source: File, newName: String, roots: List<File>): File {
    val name = newName
    require(name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' }) { "invalid rename target name" }
    val canonicalSource = source.canonicalFile
    val parent = canonicalSource.parentFile ?: error("filesystem root cannot be renamed")
    val canonicalRoots = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
    require(canonicalRoots.none { it.path == canonicalSource.path }) { "allowed root cannot be renamed" }
    val target = File(parent, name).canonicalFile
    require(target.parentFile == parent) { "rename target escapes its parent" }
    require(!target.exists()) { "rename target already exists: ${target.path}" }
    require(canonicalRoots.any { target.path == it.path || target.path.startsWith(it.path + File.separator) }) {
        "rename target is outside allowed roots: ${target.path}"
    }
    return target
}

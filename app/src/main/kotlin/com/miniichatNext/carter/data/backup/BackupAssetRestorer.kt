/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.backup

import com.miniichatNext.carter.data.avatar.AvatarPaths
import com.miniichatNext.carter.data.model.GeneratedImageStore
import com.miniichatNext.carter.util.writeAtomicFile
import java.io.File
import java.util.UUID

/** 先规划用途对应的正式路径，写入前保存旧文件，配置恢复失败时一起回滚。 */
internal class BackupAssetRestorer(
    private val filesDir: File,
    private val stage: File,
) {
    private val assets = File(stage, "data/assets")
    private val copies = linkedMapOf<File, File>()
    private val previous = linkedMapOf<File, File?>()
    private val attachmentPaths = mutableMapOf<String, File>()

    init {
        assets.listFiles()?.forEach { file ->
            require(file.isFile && file.name.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid asset name" }
        }
    }

    fun user(reference: String): String = plan(reference, AvatarPaths.user(filesDir))
    fun assistant(reference: String, id: String): String = plan(reference, AvatarPaths.assistant(filesDir, id))
    fun background(reference: String, id: String): String = plan(reference, AvatarPaths.background(filesDir, id))

    fun generated(reference: String): String {
        val match = GENERATED_REF.matchEntire(reference) ?: return reference
        val (id, ext) = match.destructured
        val target = attachmentPaths.getOrPut(reference) {
            File(GeneratedImageStore.directory(filesDir), "${UUID.randomUUID()}.$ext")
        }
        return plan("backup://assets/$id", target, GeneratedImageStore.MAX_BYTES.toLong())
    }

    fun attachment(reference: String, name: String): String {
        if (reference.startsWith("backup://generated/")) return generated(reference).also { require(it != reference) { "Invalid generated image reference" } }
        if (!reference.startsWith("backup://")) return reference
        val target = attachmentPaths.getOrPut(reference) {
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
            File(filesDir, "uploads/${UUID.randomUUID()}_$safeName")
        }
        return plan(reference, target)
    }

    private fun plan(reference: String, target: File, maxBytes: Long = Long.MAX_VALUE): String {
        if (!reference.startsWith("backup://")) return reference
        require(reference.startsWith("backup://assets/")) { "Invalid asset reference" }
        val id = reference.removePrefix("backup://assets/")
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid asset reference" }
        val source = File(assets, id)
        require(source.isFile && source.length() <= maxBytes) { "Missing or oversized referenced asset" }
        val canonical = target.canonicalFile
        require(canonical.path.startsWith(filesDir.canonicalPath + File.separator)) { "Invalid asset destination" }
        require(canonical !in copies || copies[canonical] == source) { "Conflicting asset destination" }
        copies[canonical] = source
        return canonical.absolutePath
    }

    fun install() {
        val undo = File(stage, "asset_undo").apply { mkdirs() }
        copies.entries.forEachIndexed { index, (target, source) ->
            require(!target.exists() || target.isFile) { "Asset destination is not a file" }
            val old = if (target.exists()) File(undo, index.toString()).also { target.copyTo(it) } else null
            previous[target] = old
            target.parentFile?.mkdirs()
            writeAtomicFile(target) { source.copyTo(it, overwrite = true) }
        }
    }

    fun rollback() {
        var failure: Throwable? = null
        previous.entries.toList().asReversed().forEach { (target, old) ->
            runCatching {
                if (old != null) writeAtomicFile(target) { old.copyTo(it, overwrite = true) }
                else check(!target.exists() || target.delete()) { "Asset rollback failed" }
            }.exceptionOrNull()?.let { error ->
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    private companion object {
        val GENERATED_REF = Regex("""backup://generated/([a-fA-F0-9-]{36})\.(png|jpg|webp|gif|svg|bmp)""")
    }
}

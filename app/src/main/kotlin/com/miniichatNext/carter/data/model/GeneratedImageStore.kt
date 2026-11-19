/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

internal data class MaterializedImages(val content: String, val attachments: List<Attachment>, val replacements: Map<String, String> = emptyMap())

internal object GeneratedImageStore {
    private val pattern = Regex("""!\[([^\]]*)]\(data:image/(png|jpeg|webp|gif);base64,([A-Za-z0-9+/=\r\n]+)\)""", RegexOption.IGNORE_CASE)

    fun materialize(content: String, filesDir: File): MaterializedImages {
        val attachments = mutableListOf<Attachment>()
        val replacements = mutableMapOf<String, String>()
        val rewritten = pattern.replace(content) { match ->
            val encoded = match.groupValues[3]
            if (encoded.length > 28 * 1024 * 1024) return@replace match.value
            val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return@replace match.value
            if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) return@replace match.value
            val root = File(filesDir, "generated-images")
            check(!Files.isSymbolicLink(root.toPath()))
            check(root.isDirectory || root.mkdirs())
            val extension = match.groupValues[2].lowercase().replace("jpeg", "jpg")
            val target = File(root, "${UUID.randomUUID()}.$extension")
            target.writeBytes(bytes)
            val uri = target.toURI().toString()
            attachments += Attachment(type = "image", uri = uri, name = target.name,
                mimeType = "image/" + if (extension == "jpg") "jpeg" else extension, sizeBytes = bytes.size.toLong())
            "![${match.groupValues[1]}]($uri)".also { replacements[match.value] = it }
        }
        return MaterializedImages(rewritten, attachments, replacements)
    }
}

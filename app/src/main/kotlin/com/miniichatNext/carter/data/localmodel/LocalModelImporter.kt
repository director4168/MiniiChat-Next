/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import com.miniichatNext.carter.R
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class LocalModelImporter(private val context: Context) {
    suspend fun importFile(uri: Uri, progress: (Long, Long) -> Unit,
        onSource: (String, Long) -> Unit, onVerifying: () -> Unit): LocalModelRecord {
        val resolver = context.contentResolver
        require(uri.scheme == ContentResolver.SCHEME_CONTENT || isPublicFile(uri)) {
            context.getString(R.string.local_model_invalid_source)
        }
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } else size = File(requireNotNull(uri.path)).length()
        onSource(name, size)
        val input = resolver.openInputStream(uri) ?: error(context.getString(R.string.local_model_invalid_source))
        return input.use { copy(it, name, size, progress, onVerifying) }
    }

    suspend fun download(url: String, progress: (Long, Long) -> Unit,
        onSource: (String, Long) -> Unit, onVerifying: () -> Unit): LocalModelRecord {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        require(uri != null && uri.host != null && uri.userInfo == null && uri.scheme in listOf("http", "https")) {
            context.getString(R.string.local_model_invalid_url)
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val status = connection.responseCode
            check(status == HttpURLConnection.HTTP_OK) { context.getString(R.string.local_model_http_error, status) }
            val name = uri.rawPath.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: connection.url.path.substringAfterLast('/')
            val fileName = Uri.decode(name)
            val total = connection.contentLengthLong
            onSource(fileName, total)
            return connection.inputStream.use { copy(it, fileName, total, progress, onVerifying) }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copy(input: InputStream, sourceName: String, expected: Long,
        progress: (Long, Long) -> Unit, onVerifying: () -> Unit): LocalModelRecord {
        LocalModelStore.initialize(context)
        val root = LocalModelStore.root(context)
        check(root.isDirectory || root.mkdirs())
        check(expected <= root.usableSpace) { context.getString(R.string.local_model_no_space) }
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".import-$id")
        check(staging.mkdir())
        try {
            val stream = input.buffered(256 * 1024)
            val header = ByteArray(24)
            var offset = 0
            while (offset < header.size) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            require(offset == header.size && GgufHeader.isSupported(header)) { context.getString(R.string.local_model_invalid_gguf) }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(header)
            var copied = header.size.toLong()
            var lastReport = SystemClock.elapsedRealtime()
            File(staging, "model.gguf").outputStream().buffered(256 * 1024).use { output ->
                output.write(header)
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= 250L) {
                        progress(copied, expected)
                        lastReport = now
                    }
                }
            }
            progress(copied, if (expected > 0) expected else copied)
            require(expected < 0 || copied == expected) { context.getString(R.string.local_model_incomplete) }
            val modelFile = File(staging, "model.gguf")
            currentCoroutineContext().ensureActive()
            onVerifying()
            require(LlamaJni.isValidModel(modelFile.absolutePath.encodeToByteArray())) {
                context.getString(R.string.local_model_invalid_gguf)
            }
            currentCoroutineContext().ensureActive()
            val name = sourceName.substringAfterLast('/').replace(Regex("(?i)\\.gguf$"), "")
                .filterNot { it.isISOControl() }.take(160).ifBlank { "GGUF" }
            return LocalModelStore.publish(context, staging, LocalModelRecord(id, name, copied, digest.digest().joinToString("") { "%02x".format(it) }))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun isPublicFile(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_FILE || !uri.authority.isNullOrEmpty() || !GgufHeader.isModelFileName(uri.path.orEmpty())) return false
        val path = uri.path?.let { File(it).canonicalPath } ?: return false
        val root = Environment.getExternalStorageDirectory().canonicalPath
        return path.startsWith("$root/") && !path.startsWith("$root/Android/")
    }
}

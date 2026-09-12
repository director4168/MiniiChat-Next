package com.miniichatNext.carter

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.Base64

/**
 * DocumentsProvider机制
 *
 * 说明：
 * - 只暴露[android.content.pm.ApplicationInfo.dataDir]这棵子树，且做canonical前缀校验阻止任何路径逃逸
 * - 只读实现（openDocument 仅允许 mode == "r"），不提供写入/删除/创建
 * - 通过android:permission="android.permission.MANAGE_DOCUMENTS"保护，只有系统DocumentsUI 才有权访问
 */
class AppDataDocumentsProvider : DocumentsProvider() {

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    )

    private val defaultDocumentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    )

    private val appDataRoot: File
        get() {
            val ctx = context ?: throw FileNotFoundException("Context is unavailable")
            return ctx.applicationInfo?.dataDir?.let { File(it) }
                ?: throw FileNotFoundException("Context is unavailable")
        }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val root = appDataRoot
        val cols = projection ?: defaultRootProjection
        return MatrixCursor(cols).apply {
            val row = newRow()
            // 只写 projection 里出现的列：某些 ROM 的自定义 projection 会少列，
            // MatrixCursor.RowBuilder.add(name, …) 对不存在的列会抛 IllegalArgumentException
            cols.forEach { col ->
                row.add(
                    col,
                    when (col) {
                        Root.COLUMN_ROOT_ID -> ROOT_ID
                        Root.COLUMN_MIME_TYPES -> "*/*"
                        Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
                        Root.COLUMN_ICON -> R.mipmap.ic_launcher
                        Root.COLUMN_TITLE -> "MiniiChat Next"
                        Root.COLUMN_SUMMARY -> root.absolutePath
                        Root.COLUMN_DOCUMENT_ID -> ROOT_DOCUMENT_ID
                        Root.COLUMN_AVAILABLE_BYTES -> root.freeSpace
                        else -> null
                    }
                )
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = resolveDocumentId(documentId)
        val cols = projection ?: defaultDocumentProjection
        return MatrixCursor(cols).apply { includeFile(file, cols) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = resolveDocumentId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cols = projection ?: defaultDocumentProjection
        return MatrixCursor(cols).apply {
            parent.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.forEach { includeFile(it, cols) }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("This provider is read-only")
        val file = resolveDocumentId(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getDocumentType(documentId: String): String =
        getMimeType(resolveDocumentId(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        runCatching {
            val parent = resolveDocumentId(parentDocumentId).canonicalFile
            val child = resolveDocumentId(documentId).canonicalFile
            child == parent || child.path.startsWith(parent.path + File.separator)
        }.getOrDefault(false)

    // ---------- internals ----------

    private fun MatrixCursor.includeFile(file: File, cols: Array<out String>) {
        val root = runCatching { appDataRoot.canonicalFile }.getOrNull()
        val isRoot = root != null && runCatching { file.canonicalFile == root }.getOrDefault(false)
        val row = newRow()
        cols.forEach { col ->
            row.add(
                col,
                when (col) {
                    Document.COLUMN_DOCUMENT_ID -> getDocumentId(file)
                    Document.COLUMN_DISPLAY_NAME -> if (isRoot) "MiniiChat Next" else file.name
                    Document.COLUMN_MIME_TYPE -> getMimeType(file)
                    Document.COLUMN_FLAGS -> 0
                    Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    else -> null
                }
            )
        }
    }

    private fun resolveDocumentId(documentId: String): File {
        val root = appDataRoot.canonicalFile
        val target = when {
            documentId == ROOT_DOCUMENT_ID -> root
            documentId.startsWith(DOCUMENT_PREFIX) -> {
                val encoded = documentId.removePrefix(DOCUMENT_PREFIX)
                val relative = decodePath(encoded)
                // 显式拒绝绝对路径与上跳段，避免绕过canonical校验
                if (relative.startsWith("/") || relative.split('/').any { it == ".." }) {
                    throw FileNotFoundException("Blocked document id: $documentId")
                }
                File(root, relative)
            }
            else -> throw FileNotFoundException("Invalid document id: $documentId")
        }.canonicalFile

        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Blocked path escape: $documentId")
        }
        if (!target.exists()) throw FileNotFoundException("Not found: $documentId")
        return target
    }

    private fun getDocumentId(file: File): String {
        val root = appDataRoot.canonicalFile
        val canonical = file.canonicalFile
        if (canonical == root) return ROOT_DOCUMENT_ID
        val relative = canonical.relativeTo(root).path
        return DOCUMENT_PREFIX + encodePath(relative)
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun encodePath(path: String): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toByteArray(Charsets.UTF_8))
    } else {
        @Suppress("DEPRECATION")
        android.util.Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    private fun decodePath(encoded: String): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    } else {
        @Suppress("DEPRECATION")
        String(
            android.util.Base64.decode(
                encoded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            ),
            Charsets.UTF_8
        )
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".documents"
        const val ROOT_DOCUMENT_ID = "root"
        private const val ROOT_ID = "app_data"
        private const val DOCUMENT_PREFIX = "root:"
    }
}

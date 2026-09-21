package com.miniichatNext.carter

import android.app.Activity
import android.content.Context
import android.content.Intent
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
 * DocumentsProvider机制（可读写）
 *
 * 只暴露[android.content.pm.ApplicationInfo.dataDir]这棵子树，且做canonical前缀校验阻止任何路径逃逸
 * 支持读/写/新建/重命名/删除
 * 通过android:permission="android.permission.MANAGE_DOCUMENTS"保护，只有系统DocumentsUI/文件管理器才有权访问
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
            // 只写projection里出现的列：某些ROM的自定义projection会少列，
            // MatrixCursor.RowBuilder.add(name, …)对不存在的列会抛IllegalArgumentException
            cols.forEach { col ->
                row.add(
                    col,
                    when (col) {
                        Root.COLUMN_ROOT_ID -> ROOT_ID
                        Root.COLUMN_MIME_TYPES -> "*/*"
                        Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or
                            Root.FLAG_SUPPORTS_IS_CHILD or
                            Root.FLAG_SUPPORTS_CREATE
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
        val root = appDataRoot.canonicalFile
        val file = resolveDocumentId(documentId, requireExists = mode == "r")
        if (file.isDirectory) throw FileNotFoundException("Not a file: $documentId")
        if (file == root) throw FileNotFoundException("Refusing to open the root as a file")

        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("Unsupported mode: $mode")
        }
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = resolveDocumentId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val safeName = sanitizeName(displayName)
        val target = File(parent, safeName).canonicalFile
        ensureInside(target)

        // 同名时按“名字 (1).ext”找一个可用的
        val finalTarget = if (target.exists()) uniqueChild(target) else target
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!finalTarget.mkdirs() && !finalTarget.isDirectory) {
                throw FileNotFoundException("Failed to create directory: $safeName")
            }
        } else {
            finalTarget.parentFile?.mkdirs()
            // 先建空文件（对于空文件，某些ROM的DocumentsUI会认为创建失败，所以这里直接落一个空文件）
            if (!finalTarget.createNewFile() && !finalTarget.isFile) {
                throw FileNotFoundException("Failed to create file: $safeName")
            }
        }
        return getDocumentId(finalTarget)
    }

    override fun deleteDocument(documentId: String) {
        val target = resolveDocumentId(documentId)
        if (target == appDataRoot.canonicalFile) {
            throw FileNotFoundException("Refusing to delete the root")
        }
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) throw FileNotFoundException("Failed to delete: $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = resolveDocumentId(documentId)
        if (source == appDataRoot.canonicalFile) {
            throw FileNotFoundException("Refusing to rename the root")
        }
        val parent = source.parentFile ?: throw FileNotFoundException("No parent for: $documentId")
        val target = File(parent, sanitizeName(displayName)).canonicalFile
        ensureInside(target)
        if (target == source) return getDocumentId(source)
        val finalTarget = if (target.exists()) uniqueChild(target) else target
        if (!source.renameTo(finalTarget)) throw FileNotFoundException("Failed to rename: $documentId")
        return getDocumentId(finalTarget)
    }

    override fun getDocumentType(documentId: String): String =
        getMimeType(resolveDocumentId(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        runCatching {
            val parent = resolveDocumentId(parentDocumentId).canonicalFile
            val child = resolveDocumentId(documentId).canonicalFile
            child == parent || child.path.startsWith(parent.path + File.separator)
        }.getOrDefault(false)


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
                    Document.COLUMN_FLAGS -> documentFlags(file, isRoot)
                    Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    else -> null
                }
            )
        }
    }

    private fun documentFlags(file: File, isRoot: Boolean): Int {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot) {
            // 根目录本身不允许删除/重命名
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        return flags
    }

    /** 创建/重命名时的名字消毒：去掉分隔符与控制字符，拒绝空名 */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .trim('.')
        if (cleaned.isEmpty()) throw FileNotFoundException("Invalid display name: $raw")
        return cleaned
    }

    private fun uniqueChild(target: File): File {
        val stem = target.nameWithoutExtension
        val ext = target.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do {
            candidate = File(target.parentFile, "$stem ($n)$ext")
            n++
        } while (candidate.exists())
        return candidate
    }

    private fun ensureInside(target: File) {
        val root = appDataRoot.canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Blocked path escape: ${target.path}")
        }
    }

    private fun resolveDocumentId(documentId: String, requireExists: Boolean = true): File {
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

        ensureInside(target)
        if (requireExists && !target.exists()) throw FileNotFoundException("Not found: $documentId")
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

        /**
         * 用系统文件管理器（DocumentsUI/MT管理器等）打开本应用的私有目录根
         * 返回false表示设备上没有能处理该Intent的文件管理器
         */
        fun openRoot(context: Context): Boolean = runCatching {
            val authority = context.packageName + AUTHORITY_SUFFIX
            val uri = android.provider.DocumentsContract.buildRootUri(authority, ROOT_ID)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, Root.MIME_TYPE_ITEM)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

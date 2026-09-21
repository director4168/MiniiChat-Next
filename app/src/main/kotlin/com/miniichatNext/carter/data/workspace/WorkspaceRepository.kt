package com.miniichatNext.carter.data.workspace

import android.content.Context
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class WorkspaceRepository(
    private val context: Context,
    private val store: WorkspaceStore = WorkspaceStore(context),
    val manager: WorkspaceManager = defaultManager(context),
    private val rootfsInstaller: RootfsInstaller = RootfsInstaller(manager),
) {
    val workspacesFlow: Flow<List<WorkspaceEntity>> = store.workspacesFlow

    suspend fun list(): List<WorkspaceEntity> = store.snapshot()

    suspend fun getById(id: String): WorkspaceEntity? = store.snapshot().firstOrNull { it.id == id }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        for (workspace in store.snapshot()) {
            if (!manager.workspaceDir(workspace.root).exists()) {
                // 目录缺失时不删记录，仅标记BROKEN，避免误删用户工作区
                DebugLog.w(TAG, "workspace dir missing: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name) &&
                !manager.hasRootfs(workspace.root)
            ) {
                DebugLog.w(TAG, "rootfs missing, reset shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun create(name: String): WorkspaceEntity {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) { "Workspace name already exists: $finalName" }
        val entity = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(entity.root)
        store.upsert(entity)
        return entity
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) { "Workspace name already exists: $finalName" }
        store.upsert(workspace.copy(name = finalName, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun touch(id: String) {
        val workspace = getById(id) ?: return
        store.upsert(workspace.copy(lastAccessAt = System.currentTimeMillis()))
    }

    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return store.snapshot().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        store.upsert(
            workspace.copy(
                toolApprovals = JSON.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = getById(id) ?: return false
        updateShellState(workspace.id, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible让协程取消转成线程中断，打断install内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace.id, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateShellState(workspace.id, workspace.shellStatus) }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) { updateShellState(workspace.id, workspace.shellStatus) }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            DebugLog.e(TAG, "installRootfs failed: id=${workspace.id} url=$url", e)
            updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(id: String, path: String): String = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /** FILES区走readText（自带大小保护）；LINUX区经exportFile读入内存，需显式限长 */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) { "文件过大，无法预览 (${size} bytes)" }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(id: String, area: WorkspaceStorageArea, path: String): Long =
        withContext(Dispatchers.IO) {
            val workspace = getById(id) ?: error("Workspace not found: $id")
            manager.fileSize(workspace.root, path, area)
        }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    suspend fun rootfsFileSize(id: String, path: String): Long = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    suspend fun exportRootfsFile(id: String, path: String, outputStream: OutputStream) =
        withContext(Dispatchers.IO) {
            val workspace = getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspace(workspace.root)
            manager.exportRootfsFile(workspace.root, path, outputStream)
        }

    suspend fun readRootfsBytes(
        id: String,
        path: String,
        maxBytes: Long = MAX_PREVIEW_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val size = manager.rootfsFileSize(workspace.root, path)
        require(size <= maxBytes) {
            "File is too large to read: $path (${size / 1024 / 1024}MB, max ${maxBytes / 1024 / 1024}MB). " +
                "Use shell commands like head, tail or grep to read parts of it."
        }
        ByteArrayOutputStream(size.toInt().coerceAtLeast(0)).use { out ->
            manager.exportRootfsFile(workspace.root, path, out)
            out.toByteArray()
        }
    }

    suspend fun readTextInRootfs(id: String, path: String): String = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val location = manager.resolveRootfsPath(workspace.root, path)
        manager.readTextInDir(location.rootDir, location.relativePath)
    }

    suspend fun writeTextInRootfs(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val location = manager.resolveRootfsPath(workspace.root, path)
        require(location.relativePath.isNotBlank()) { "Path is a directory: $path" }
        manager.writeTextInDir(location.rootDir, location.relativePath, text, overwrite)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: return@withContext false
        manager.deleteFile(workspace.root, path, recursive, area)
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = getById(id) ?: error("Workspace not found: $id")
        // runInterruptible让协程取消转化为线程中断，打断阻塞的Process.waitFor并杀进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = getById(id) ?: return false
        store.delete(id)
        withContext(Dispatchers.IO) { manager.deleteWorkspace(workspace.root) }
        return true
    }

    private suspend fun updateShellState(workspaceId: String, shellStatus: String) {
        val workspace = getById(workspaceId) ?: return
        store.upsert(workspace.copy(shellStatus = shellStatus, updatedAt = System.currentTimeMillis()))
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun baseDir(context: Context): File = File(context.filesDir, "workspaces").apply { mkdirs() }

        fun defaultManager(context: Context): WorkspaceManager = WorkspaceManager(
            baseDir = baseDir(context),
            shellRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir)),
            bindMounts = listOf(
                WorkspaceBindMount(File(context.filesDir, "skills"), "/skills"),
            ),
        )
    }
}

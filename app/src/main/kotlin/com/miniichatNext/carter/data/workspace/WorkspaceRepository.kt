/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import android.content.Context
import com.miniichatNext.carter.util.deleteTreeNoFollow
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.workspace.WorkspaceTerminalSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class WorkspaceRepository(
    private val context: Context,
    private val store: WorkspaceStore = WorkspaceStore(context),
    val manager: WorkspaceManager = defaultManager(context),
    /** 给UI用：当前Repository的WorkspaceConfig（含maxReadBytes等限制） */
    val config: WorkspaceConfig = WorkspaceConfig(),
    private val rootfsInstaller: RootfsInstaller = RootfsInstaller(manager),
) {
    val workspacesFlow: Flow<List<WorkspaceEntity>> = store.workspacesFlow

    suspend fun list(): List<WorkspaceEntity> = store.snapshot()

    suspend fun getById(id: String): WorkspaceEntity? = store.snapshot().firstOrNull { it.id == id }

    internal suspend fun isReadyForTools(id: String): Boolean = getById(id)?.let { workspace ->
        workspace.shellStatus == WorkspaceShellStatus.READY.name && withContext(Dispatchers.IO) { canUseRootfs(workspace.root) }
    } == true

    private suspend fun <T> withWorkspaceIo(id: String, block: (WorkspaceEntity) -> T): T {
        val root = (getById(id) ?: error("Workspace not found: $id")).root
        return WorkspaceRuntimeCoordinator.withWorkspace(root) {
            val current = getById(id) ?: error("Workspace not found: $id")
            check(current.root == root) { "Workspace has changed" }
            runInterruptible(Dispatchers.IO) { block(current) }
        }
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        for (workspace in store.snapshot()) {
            WorkspaceRuntimeCoordinator.withLifecycle(workspace.id) {
                val backup = File(manager.tempDir(workspace.root), "rootfs-backup")
                val recovery = runCatching {
                    WorkspaceRuntimeCoordinator.withWorkspace(workspace.root) {
                        recoverRootfsBackup(manager.linuxDir(workspace.root), backup)
                    }
                }.onFailure { if (it is CancellationException) throw it }
                if (recovery.isFailure) {
                    DebugLog.e(TAG, "rootfs backup recovery failed: id=${workspace.id}", recovery.exceptionOrNull())
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                } else if (!manager.workspaceDir(workspace.root).isDirectory) {
                    DebugLog.w(TAG, "workspace dir missing: id=${workspace.id}, root=${workspace.root}")
                    if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                        updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                    }
                } else {
                    val statusName = workspace.shellStatus
                    if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name) &&
                        !canUseRootfs(workspace.root)
                    ) {
                        DebugLog.w(TAG, "rootfs missing, reset shell status: id=${workspace.id}")
                        updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
                    }
                }
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

    suspend fun rename(id: String, name: String): Boolean = WorkspaceRuntimeCoordinator.withLifecycle(id) {
        val workspace = getById(id) ?: return@withLifecycle false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) { "Workspace name already exists: $finalName" }
        store.update(id) { it.copy(name = finalName, updatedAt = System.currentTimeMillis()) }
        true
    }

    suspend fun touch(id: String) {
        WorkspaceRuntimeCoordinator.withLifecycle(id) {
            store.update(id) { it.copy(lastAccessAt = System.currentTimeMillis()) }
        }
    }

    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return store.snapshot().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean =
        WorkspaceRuntimeCoordinator.withLifecycle(id) {
            store.update(id) { workspace ->
                val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
                workspace.copy(
                    toolApprovals = JSON.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), overrides),
                    updatedAt = System.currentTimeMillis(),
                )
            } != null
        }

    val rootfsAbi: String? by lazy { RootfsLayout.prootAbi(File(context.applicationInfo.nativeLibraryDir)) }

    fun canUseRootfs(root: String): Boolean = rootfsAbi?.let { RootfsLayout.matchesAbi(manager.linuxDir(root), it) } == true

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean = WorkspaceRuntimeCoordinator.withLifecycle(id) {
        val workspace = getById(id) ?: return@withLifecycle false
        val abi = requireNotNull(rootfsAbi) { context.getString(R.string.workspace_rootfs_unsupported_abi) }
        val originalStatus = workspace.shellStatus
        updateShellState(workspace.id, WorkspaceShellStatus.INSTALLING.name)
        var blockedRoot: String? = null
        try {
            rootfsInstaller.install(workspace.root, url, onProgress, expectedAbi = abi) {
                WorkspaceRuntimeCoordinator.block(id)
                if (workspace.root != id) WorkspaceRuntimeCoordinator.block(workspace.root)
                blockedRoot = workspace.root
                try {
                    WorkspaceRuntimeCoordinator.withWorkspace(workspace.root, allowBlocked = true) {
                        WorkspaceTerminalSessionManager.closeWorkspace(workspace.root)
                    }
                } catch (failure: Throwable) {
                    WorkspaceRuntimeCoordinator.unblock(id)
                    if (workspace.root != id) WorkspaceRuntimeCoordinator.unblock(workspace.root)
                    blockedRoot = null
                    throw failure
                }
            }
            updateShellState(workspace.id, WorkspaceShellStatus.READY.name)
            true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                updateShellState(workspace.id, if (manager.hasRootfs(workspace.root)) WorkspaceShellStatus.READY.name else originalStatus)
            }
            throw cancelled
        } catch (interrupted: InterruptedException) {
            withContext(NonCancellable) {
                updateShellState(workspace.id, if (manager.hasRootfs(workspace.root)) WorkspaceShellStatus.READY.name else originalStatus)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(interrupted) }
        } catch (failure: Throwable) {
            DebugLog.e(TAG, "installRootfs failed: id=${workspace.id} url=$url", failure)
            updateShellState(workspace.id, if (manager.hasRootfs(workspace.root)) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.BROKEN.name)
            throw failure
        } finally {
            if (blockedRoot != null) {
                WorkspaceRuntimeCoordinator.unblock(id)
                if (workspace.root != id) WorkspaceRuntimeCoordinator.unblock(workspace.root)
            }
        }
    }

    suspend fun listFiles(id: String, area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry> =
        withWorkspaceIo(id) { manager.ensureWorkspace(it.root); manager.listFiles(it.root, path, area) }

    suspend fun readText(id: String, path: String): String =
        withWorkspaceIo(id) { manager.ensureWorkspace(it.root); manager.readText(it.root, path) }

    suspend fun writeText(id: String, path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        withWorkspaceIo(id) { manager.ensureWorkspace(it.root); manager.writeText(it.root, path, text, overwrite) }

    /**
     * 带area的重载：FILES区走manager.writeText；LINUX区走manager.writeTextInDir(linuxDir)。
     * FILES区可见root真实文件名；LINUX区path相对rootfs根。
     */
    suspend fun writeText(id: String, area: WorkspaceStorageArea, path: String, text: String): WorkspaceFileEntry =
        withWorkspaceIo(id) { workspace ->
            manager.ensureWorkspace(workspace.root)
            if (area == WorkspaceStorageArea.LINUX) manager.writeTextInDir(manager.linuxDir(workspace.root), path, text)
            else manager.writeText(workspace.root, path, text)
        }

    fun previewTextLimit(area: WorkspaceStorageArea): Long = if (area == WorkspaceStorageArea.FILES)
        minOf(config.maxReadBytes, config.maxWriteBytes) else MAX_PREVIEW_BYTES

    suspend fun readTextForPreview(id: String, area: WorkspaceStorageArea, path: String): String =
        withWorkspaceIo(id) { workspace ->
            val bytes = manager.readBytes(workspace.root, area, path, previewTextLimit(area).toInt())
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }

    suspend fun savePreviewText(id: String, path: String, expected: String, text: String): WorkspaceFileEntry =
        withWorkspaceIo(id) { manager.savePreviewText(it.root, path, expected, text) }

    suspend fun importFile(
        id: String, area: WorkspaceStorageArea, destinationPath: String, fileName: String, inputStream: InputStream,
    ): WorkspaceFileEntry = withWorkspaceIo(id) {
        manager.ensureWorkspace(it.root)
        manager.importFile(it.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(id: String, area: WorkspaceStorageArea, path: String): Long =
        withWorkspaceIo(id) { manager.fileSize(it.root, path, area) }

    suspend fun readBytes(id: String, area: WorkspaceStorageArea, path: String, maxBytes: Int = 20 * 1024 * 1024): ByteArray =
        withWorkspaceIo(id) { manager.readBytes(it.root, area, path, maxBytes) }

    suspend fun exportFile(id: String, area: WorkspaceStorageArea, path: String, outputStream: OutputStream) {
        withWorkspaceIo(id) { manager.exportFile(it.root, path, area, outputStream) }
    }

    suspend fun rootfsFileSize(id: String, path: String): Long =
        withWorkspaceIo(id) { manager.ensureWorkspace(it.root); manager.rootfsFileSize(it.root, path) }

    suspend fun exportRootfsFile(id: String, path: String, outputStream: OutputStream) {
        withWorkspaceIo(id) { workspace ->
            manager.ensureWorkspace(workspace.root)
            manager.exportRootfsFile(workspace.root, path, outputStream)
        }
    }

    suspend fun readRootfsBytes(id: String, path: String, maxBytes: Long = MAX_PREVIEW_BYTES): ByteArray =
        withWorkspaceIo(id) { manager.readRootfsBytes(it.root, path, maxBytes.toInt()) }

    suspend fun editRootfsText(id: String, path: String, old: String, new: String, replaceAll: Boolean): Pair<WorkspaceFileEntry, ReplaceTextResult> =
        withWorkspaceIo(id) { manager.editRootfsText(it.root, path, old, new, replaceAll) }

    suspend fun readTextInRootfs(id: String, path: String): String = withWorkspaceIo(id) { workspace ->
        manager.ensureWorkspace(workspace.root)
        val location = manager.resolveRootfsPath(workspace.root, path)
        manager.readTextInDir(location.rootDir, location.relativePath)
    }

    suspend fun writeTextInRootfs(id: String, path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        withWorkspaceIo(id) { workspace ->
            manager.ensureWorkspace(workspace.root)
            val location = manager.resolveRootfsWritePath(workspace.root, path)
            require(location.relativePath.isNotBlank()) { "Path is a directory: $path" }
            manager.writeTextInDir(location.rootDir, location.relativePath, text, overwrite)
        }

    suspend fun deleteFile(id: String, area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        withWorkspaceIo(id) { manager.deleteFile(it.root, path, recursive, area) }

    suspend fun moveFile(id: String, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry =
        withWorkspaceIo(id) { manager.ensureWorkspace(it.root); manager.moveFile(it.root, source, target, overwrite) }

    suspend fun copyFile(id: String, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry =
        withWorkspaceIo(id) { manager.copyFile(it.root, source, target, overwrite) }

    suspend fun mkdir(id: String, path: String): WorkspaceFileEntry =
        withWorkspaceIo(id) { manager.mkdir(it.root, path) }

    suspend fun glob(id: String, pattern: String, path: String): List<WorkspaceFileEntry> =
        withWorkspaceIo(id) { manager.glob(it.root, pattern, path) }

    suspend fun grep(id: String, pattern: String, path: String, regex: Boolean, maxResults: Int): List<WorkspaceSearchMatch> =
        withWorkspaceIo(id) { manager.grep(it.root, pattern, path, regex, maxResults = maxResults) }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val root = (getById(id) ?: error("Workspace not found: $id")).root
        return WorkspaceRuntimeCoordinator.withWorkspace(root) {
            val workspace = getById(id) ?: error("Workspace not found: $id")
            runInterruptible(Dispatchers.IO) {
                check(canUseRootfs(workspace.root)) { context.getString(R.string.workspace_terminal_not_installed) }
                manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin, workspace.shellCompatibilityMode)
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = getById(id) ?: return false
        WorkspaceRuntimeCoordinator.block(id)
        if (workspace.root != id) WorkspaceRuntimeCoordinator.block(workspace.root)
        try {
            RootfsInstallManager.cancelAndJoin(id)
            WorkspaceTerminalSessionManager.closeWorkspace(workspace.root)
            return WorkspaceRuntimeCoordinator.withLifecycle(id) {
                WorkspaceRuntimeCoordinator.withWorkspace(workspace.root, allowBlocked = true) {
                    val directory = manager.workspaceDir(workspace.root)
                    if (!directory.exists()) {
                        store.delete(id)
                        return@withWorkspace true
                    }
                    val trash = File(directory.parentFile, ".deleting-${workspace.root}-${UUID.randomUUID()}")
                    check(directory.renameTo(trash)) { "Could not stage workspace directory for deletion" }
                    var recordRemoved = false
                    try {
                        store.delete(id)
                        recordRemoved = true
                        check(withContext(Dispatchers.IO) { deleteTreeNoFollow(trash) }) {
                            "Workspace data could not be fully removed"
                        }
                        true
                    } catch (failure: Throwable) {
                        val restored = !trash.exists() || trash.renameTo(directory)
                        if (!restored) {
                            DebugLog.e(TAG, "workspace delete rollback failed: id=$id path=${trash.path}", failure)
                        }
                        if (recordRemoved && restored) {
                            runCatching { store.upsert(workspace) }.onFailure(failure::addSuppressed)
                        }
                        throw failure
                    }
                }
            }
        } finally {
            WorkspaceRuntimeCoordinator.unblock(id)
            if (workspace.root != id) WorkspaceRuntimeCoordinator.unblock(workspace.root)
        }
    }

    /** shell兼容模式开关（详情页） */
    suspend fun updateShellCompatibilityMode(workspaceId: String, enabled: Boolean) {
        WorkspaceRuntimeCoordinator.withLifecycle(workspaceId) {
            store.update(workspaceId) { it.copy(shellCompatibilityMode = enabled, updatedAt = System.currentTimeMillis()) }
        }
    }

    private suspend fun updateShellState(workspaceId: String, shellStatus: String) {
        store.update(workspaceId) { it.copy(shellStatus = shellStatus, updatedAt = System.currentTimeMillis()) }
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
        private val JSON get() = AppJson.json

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

/** 清理apt/dpkg残留锁的命令（proot内执行）。 */

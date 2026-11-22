/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.util.deleteTreeNoFollow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按target长度降序，保证/a/b优先于/a匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        require(!java.nio.file.Files.isSymbolicLink(baseDir.toPath())) { "Invalid workspaces directory" }
        check(baseDir.isDirectory || baseDir.mkdirs()) { "Could not create workspaces directory" }
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return childDirectory(baseDir, root)
    }

    fun filesDir(root: String): File = childDirectory(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = childDirectory(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = childDirectory(workspaceDir(root), TEMP_DIR)

    private fun childDirectory(parent: File, name: String): File = File(parent, name).also {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "Workspace directory is a symbolic link" }
    }

    fun hasRootfs(root: String): Boolean = RootfsLayout.isUsable(linuxDir(root))

    fun deleteWorkspace(root: String): Boolean = deleteTreeNoFollow(workspaceDir(root))

    fun listAllRoots(): List<String> =
        baseDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.filter { it.matches(ROOT_NAME_REGEX) }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> = fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.copyTo(out) } }
    }

    /**
     * 把Rootfs内的绝对路径映射到宿主机真实文件。
     * bind mount的source本身是Android侧普通目录，可直接文件IO访问。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val raw = path.replace('\\', '/').trim()
        require(raw.startsWith('/') && '\u0000' !in raw) { "Rootfs path must be absolute" }
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        require(".." !in parts) { "Rootfs path must not contain parent segments" }
        val trimmed = parts.joinToString("/", prefix = "/")

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    internal fun resolveRootfsWritePath(root: String, path: String): RootfsLocation {
        val location = resolveRootfsPath(root, path)
        require(location.rootDir == filesDir(root) || location.rootDir == linuxDir(root)) { "This mount is read-only in the file tools" }
        return location
    }

    internal fun savePreviewText(root: String, path: String, expected: String, text: String): WorkspaceFileEntry =
        fileSystem.replaceTextIfUnchanged(filesDir(root), path, expected, text)

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun listRootfs(root: String, path: String): List<WorkspaceFileEntry> {
        val location = resolveRootfsPath(root, path)
        return fileSystem.list(location.rootDir, location.relativePath)
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean = fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        maxResults: Int = config.maxSearchResults,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob, maxResults)

    fun copyFile(root: String, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry =
        fileSystem.copy(filesDir(root), source, target, overwrite)

    fun mkdir(root: String, path: String): WorkspaceFileEntry = fileSystem.mkdir(filesDir(root), path)

    fun readBytes(root: String, area: WorkspaceStorageArea, path: String, maxBytes: Int): ByteArray =
        fileSystem.readBytes(areaDir(root, area), path, maxBytes)

    fun readRootfsBytes(root: String, path: String, maxBytes: Int): ByteArray {
        val (dir, relative) = resolveRootfsPath(root, path)
        return fileSystem.readBytes(dir, relative, maxBytes)
    }

    fun editRootfsText(root: String, path: String, old: String, new: String, replaceAll: Boolean): Pair<WorkspaceFileEntry, ReplaceTextResult> {
        val (dir, relative) = resolveRootfsWritePath(root, path)
        return fileSystem.editText(dir, relative, old, new, replaceAll)
    }

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = workingDir.relativeTo(filesDir(root).canonicalFile).invariantSeparatorsPath,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX) && root !in setOf(".", "..")) { "Invalid workspace root name: $root" }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    /** 按宿主目录读写（用于bind mount/Rootfs绝对路径落点） */
    fun writeTextInDir(
        dir: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
    ): WorkspaceFileEntry = fileSystem.writeText(dir, path, text, overwrite)

    fun readTextInDir(dir: File, path: String): String = fileSystem.readText(dir, path)

    fun listInDir(dir: File, path: String): List<WorkspaceFileEntry> = fileSystem.list(dir, path)

    fun fileSizeInDir(dir: File, path: String): Long =
        fileSystem.resolve(dir, path).let { require(it.isFile) { "Not a file: $path" }; it.length() }

    fun exportFileInDir(dir: File, path: String, outputStream: OutputStream) {
        val file = fileSystem.resolve(dir, path)
        require(file.isFile) { "Not a file: $path" }
        outputStream.use { out -> java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.copyTo(out) } }
    }

    fun cleanupAllTempDirs() {
        for (root in listAllRoots()) {
            deleteTreeNoFollow(tempDir(root))
            val linux = linuxDir(root)
            deleteTreeNoFollow(File(linux, "tmp"))
            val variable = File(linux, "var")
            if (!java.nio.file.Files.isSymbolicLink(variable.toPath())) deleteTreeNoFollow(File(variable, "tmp"))
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        fun rootfsShell(linuxDir: File): String = RootfsLayout.shell(linuxDir)

        /** 由宿主机透传的内核伪文件系统，只能通过shell访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

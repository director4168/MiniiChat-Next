/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

const val WORKSPACE_SERVER_ID = "workspace"

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_TOOL_TEXT_CHARS = 32 * 1024

/** 工作区工具三分类（202610162134：审批粒度从逐工具改为类别开关） */
enum class WorkspaceToolCategory {
    /** 读取：read_file/list_files/glob/grep */
    READ_FILE,
    /** 写入与编辑：write_file/edit_file/delete_file/move_file/copy_file/mkdir */
    WRITE_FILE,
    /** 执行：shell */
    EXECUTE_FILE,
}

/** 工具名→类别映射 */
fun workspaceToolCategory(name: String): WorkspaceToolCategory = when (name) {
    "workspace_read_file", "workspace_list_files", "workspace_glob", "workspace_grep" ->
        WorkspaceToolCategory.READ_FILE
    "workspace_write_file", "workspace_edit_file", "workspace_delete_file",
    "workspace_move_file", "workspace_copy_file", "workspace_mkdir" ->
        WorkspaceToolCategory.WRITE_FILE
    "workspace_shell" -> WorkspaceToolCategory.EXECUTE_FILE
    else -> WorkspaceToolCategory.WRITE_FILE
}

/** 类别审批默认值：读=false写=false执行=true */
val WorkspaceCategoryDefaultApprovals: Map<WorkspaceToolCategory, Boolean> = mapOf(
    WorkspaceToolCategory.READ_FILE to false,
    WorkspaceToolCategory.WRITE_FILE to false,
    WorkspaceToolCategory.EXECUTE_FILE to true,
)

/** 类别存储键（overrides JSON里用"category:READ_FILE"这类键） */
fun WorkspaceToolCategory.storageKey(): String = "category:" + name

/**
 * 三类归并后的审批解析：类别开关优先→per-tool精细覆盖→类别默认表。
 */
fun resolveWorkspaceToolApprovalByCategory(
    name: String,
    categoryOverrides: Map<String, Boolean>,
    perToolOverrides: Map<String, Boolean>,
): Boolean {
    val cat = workspaceToolCategory(name)
    categoryOverrides[cat.storageKey()]?.let { return it }
    perToolOverrides[name]?.let { return it }
    return WorkspaceCategoryDefaultApprovals[cat] ?: false
}

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_list_files" to false,
    "workspace_delete_file" to false,
    "workspace_move_file" to false,
    "workspace_copy_file" to false,
    "workspace_mkdir" to false,
    "workspace_glob" to false,
    "workspace_grep" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

private const val PATH_DESC = "Absolute path inside Rootfs. Use /workspace for the workspace files area."

private fun workspaceTool(name: String, description: String, schema: String) =
    McpTool(
        name = name,
        description = description,
        inputSchema = schema,
        serverId = WORKSPACE_SERVER_ID,
        builtIn = true,
    )

fun workspaceToolSpecs(): List<McpTool> = listOf(
    workspaceTool(
        "workspace_read_file",
        "Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. " +
            "Use /workspace for the workspace files area. Supports UTF-8 text files.",
        """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"}},"required":["path"]}"""
    ),
    workspaceTool(
        "workspace_write_file",
        "Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. " +
            "Use /workspace for the workspace files area.",
        """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"text":{"type":"string","description":"UTF-8 text content to write"},"overwrite":{"type":"boolean","description":"Whether to overwrite an existing file. Defaults to true."}},"required":["path","text"]}"""
    ),
    workspaceTool(
        "workspace_edit_file",
        "Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. " +
            "Use /workspace for the workspace files area. Provide old_text and new_text. By default old_text must " +
            "occur exactly once; set replace_all=true to replace every occurrence. If no exact match is found, " +
            "whitespace-tolerant line matching is attempted automatically.",
        """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"old_text":{"type":"string","description":"Exact text to replace"},"new_text":{"type":"string","description":"Replacement text"},"replace_all":{"type":"boolean","description":"Whether to replace every occurrence. Defaults to false."}},"required":["path","old_text","new_text"]}"""
    ),
    workspaceTool(
        "workspace_shell",
        "Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at " +
            "/workspace. Use cwd for a path relative to the workspace files root. " +
            "Requires Rootfs to be installed and ready.",
        """{"type":"object","properties":{"command":{"type":"string","description":"Shell command to run"},"cwd":{"type":"string","description":"Working directory relative to the workspace files root. Defaults to root."},"timeout":{"type":"integer","description":"Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."}},"required":["command"]}"""
    ),
    workspaceTool(
        "workspace_list_files",
        "List a directory inside the workspace Rootfs (files area or linux rootfs). Returns names, sizes and types.",
        """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"area":{"type":"string","enum":["files","linux"],"description":"storage area; default files"}},"required":["path"]}"""
    ),
    workspaceTool(
        "workspace_delete_file",
        "Delete a file or directory (recursive=true for directories) inside the workspace Rootfs.",
        """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"},"area":{"type":"string","enum":["files","linux"],"description":"storage area; default files"},"recursive":{"type":"boolean","description":"delete directories recursively"}},"required":["path"]}"""
    ),
    workspaceTool(
        "workspace_move_file",
        "Move/rename a file or directory inside the workspace Rootfs files area.",
        """{"type":"object","properties":{"source":{"type":"string","description":"$PATH_DESC"},"target":{"type":"string","description":"$PATH_DESC"},"overwrite":{"type":"boolean","description":"overwrite existing target"}},"required":["source","target"]}"""
    ),
    workspaceTool(
        "workspace_copy_file",
        "Copy a file inside the workspace Rootfs files area.",
        """{"type":"object","properties":{"source":{"type":"string","description":"$PATH_DESC"},"target":{"type":"string","description":"$PATH_DESC"},"overwrite":{"type":"boolean","description":"overwrite existing target"}},"required":["source","target"]}"""
    ),
    workspaceTool(
        "workspace_mkdir",
        "Create a directory (parents created automatically) inside the workspace Rootfs files area.",
        """{"type":"object","properties":{"path":{"type":"string","description":"$PATH_DESC"}},"required":["path"]}"""
    ),
    workspaceTool(
        "workspace_glob",
        "Glob-pattern file search inside the workspace Rootfs files area (e.g. **/*.kt).",
        """{"type":"object","properties":{"pattern":{"type":"string","description":"glob pattern"},"path":{"type":"string","description":"base directory"}},"required":["pattern"]}"""
    ),
    workspaceTool(
        "workspace_grep",
        "Search file contents inside the workspace Rootfs files area (literal or regex).",
        """{"type":"object","properties":{"pattern":{"type":"string","description":"text or regex pattern"},"path":{"type":"string","description":"base directory"},"regex":{"type":"boolean","description":"treat pattern as regex"},"max_results":{"type":"integer","description":"max matches"}},"required":["pattern"]}"""
    ),
)

fun isWorkspaceTool(name: String): Boolean = workspaceToolSpecs().any { it.name == name }

suspend fun executeWorkspaceTool(
    toolName: String,
    argumentsJson: String,
    workspaceId: String,
    repository: WorkspaceRepository,
    defaultCwd: String? = null,
): McpToolResult = runCatching {
    // 未安装rootfs（DISABLED/BROKEN/INSTALLING）的工作区不允许执行任何workspace工具
    if (!repository.isReadyForTools(workspaceId)) {
        return@runCatching fail("workspace is not ready (rootfs not installed); install it in Settings > Workspace first")
    }
    val args = parseArgs(argumentsJson)
    when (toolName) {
        "workspace_read_file" -> readFile(args, workspaceId, repository)
        "workspace_write_file" -> writeFile(args, workspaceId, repository)
        "workspace_edit_file" -> editFile(args, workspaceId, repository)
        "workspace_shell" -> shell(args, workspaceId, repository, defaultCwd)
        "workspace_list_files" -> listFiles(args, workspaceId, repository)
        "workspace_delete_file" -> deleteFile(args, workspaceId, repository)
        "workspace_move_file" -> moveFile(args, workspaceId, repository)
        "workspace_copy_file" -> copyFile(args, workspaceId, repository)
        "workspace_mkdir" -> mkdir(args, workspaceId, repository)
        "workspace_glob" -> glob(args, workspaceId, repository)
        "workspace_grep" -> grep(args, workspaceId, repository)
        else -> fail("unknown workspace tool: $toolName")
    }
}.getOrElse { if (it is CancellationException || it is InterruptedException || it is Error) throw it else fail(it.message ?: "error") }


private fun parseArgs(argumentsJson: String): Map<String, String> {
    if (argumentsJson.isBlank()) return emptyMap()
    val obj = requireNotNull(AppJson.parseObjectOrNull(argumentsJson)) { "Invalid tool arguments" }
    return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
}

private fun Map<String, String>.requireArg(name: String): String =
    this[name]?.takeIf { it.isNotBlank() } ?: error("$name is required")

private fun Map<String, String>.absolutePath(name: String): String {
    val path = this[name]?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun workspaceRelativePath(raw: String): String {
    val normalized = raw.replace('\\', '/').trim()
    val path = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    return (if (normalized.startsWith('/') && path.firstOrNull() == "workspace") path.drop(1) else path).joinToString("/")
}

private suspend fun readFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val bytes = repository.readRootfsBytes(workspaceId, path, MAX_READ_FILE_BYTES)
    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    return ok(buildString {
        append("{\"path\":").append(jsonString(path)).append(",\"text\":")
        append(jsonString(text.take(MAX_TOOL_TEXT_CHARS)))
        if (text.length > MAX_TOOL_TEXT_CHARS) append(",\"truncated\":true")
        append('}')
    })
}

private suspend fun writeFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val text = args["text"] ?: error("text is required")
    val overwrite = args["overwrite"]?.toBooleanStrictOrNull() ?: true
    val entry = repository.writeTextInRootfs(workspaceId, path, text, overwrite)
    return ok(
        "{\"path\":${jsonString(entry.path)},\"sizeBytes\":${entry.sizeBytes},\"updatedAt\":${entry.updatedAt}}"
    )
}

private suspend fun editFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val oldText = args["old_text"] ?: error("old_text is required")
    val newText = args["new_text"] ?: error("new_text is required")
    val replaceAll = args["replace_all"]?.toBooleanStrictOrNull() ?: false
    require(oldText.isNotEmpty()) { "old_text must not be empty" }

    val (entry, result) = repository.editRootfsText(workspaceId, path, oldText, newText, replaceAll)
    return ok(
        buildString {
            append("{\"path\":").append(jsonString(entry.path)).append(",\"replacements\":").append(result.replacements)
            if (result.strategy != ExactReplacer.name) append(",\"matchStrategy\":\"").append(result.strategy).append('"')
            append(",\"sizeBytes\":").append(entry.sizeBytes)
            append(",\"updatedAt\":").append(entry.updatedAt)
            append('}')
        }
    )
}

private suspend fun shell(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
    defaultCwd: String?,
): McpToolResult {
    val command = args.requireArg("command")
    val cwd = workspaceRelativePath(args["cwd"] ?: defaultCwd.orEmpty())
    val timeoutMillis = args["timeout"]?.toLongOrNull()
        ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
        ?.times(1_000L)
        ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
    val result = repository.executeCommand(workspaceId, command, cwd, timeoutMillis)
    val payload = buildString {
        append("{\"exitCode\":").append(result.exitCode)
        append(",\"stdout\":").append(jsonString(result.stdout))
        append(",\"stderr\":").append(jsonString(result.stderr))
        append(",\"timedOut\":").append(result.timedOut)
        if (result.truncated) append(",\"truncated\":true")
        append('}')
    }
    return if (result.exitCode == 0 && !result.timedOut) ok(payload) else McpToolResult(
        ok = false,
        content = payload,
        error = if (result.timedOut) "command timed out" else "exit code ${result.exitCode}",
    )
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

private fun ok(text: String) = McpToolResult(ok = true, content = text)
private fun fail(msg: String) = McpToolResult(ok = false, content = "", error = msg)

private fun Map<String, String>.area(): WorkspaceStorageArea = when (this["area"]) {
    null, "files" -> WorkspaceStorageArea.FILES
    "linux" -> WorkspaceStorageArea.LINUX
    else -> error("Invalid workspace storage area")
}

private fun relativePath(path: String, area: WorkspaceStorageArea): String =
    if (area == WorkspaceStorageArea.FILES) workspaceRelativePath(path) else path.trimStart('/')

private suspend fun listFiles(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val path = args.absolutePath("path")
    val area = args.area()
    val entries = repository.listFiles(id, area, relativePath(path, area))
    return ok(buildString {
        appendLine("${entries.size} entries in $path")
        entries.forEach { appendLine(if (it.isDirectory) "dir ${it.name}" else "file ${it.name} (${it.sizeBytes} bytes)") }
    }.take(32 * 1024))
}

private suspend fun deleteFile(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val path = args.absolutePath("path")
    val area = args.area()
    return if (repository.deleteFile(id, area, relativePath(path, area), args["recursive"] == "true")) ok("deleted $path") else fail("not found: $path")
}

private suspend fun moveFile(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val source = args.absolutePath("source")
    val target = args.absolutePath("target")
    repository.moveFile(id, workspaceRelativePath(source), workspaceRelativePath(target), args["overwrite"] == "true")
    return ok("moved $source -> $target")
}

private suspend fun copyFile(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val source = args.absolutePath("source")
    val target = args.absolutePath("target")
    val file = repository.copyFile(id, workspaceRelativePath(source), workspaceRelativePath(target), args["overwrite"] == "true")
    return ok("copied $source -> $target (${file.sizeBytes} bytes)")
}

private suspend fun mkdir(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val path = args.absolutePath("path")
    repository.mkdir(id, workspaceRelativePath(path))
    return ok("directory ready: $path")
}

private suspend fun glob(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val pattern = args.requireArg("pattern")
    val entries = repository.glob(id, pattern, workspaceRelativePath(args["path"].orEmpty()))
    return ok(entries.joinToString("\n", "${entries.size} match(es)\n") { it.path }.take(32 * 1024))
}

private suspend fun grep(args: Map<String, String>, id: String, repository: WorkspaceRepository): McpToolResult {
    val entries = repository.grep(id, args.requireArg("pattern"), workspaceRelativePath(args["path"].orEmpty()),
        args["regex"] == "true", args["max_results"]?.toIntOrNull() ?: 100)
    return ok(entries.joinToString("\n", "${entries.size} match(es)\n") { "${it.path}:${it.line}: ${it.text}" }.take(32 * 1024))
}

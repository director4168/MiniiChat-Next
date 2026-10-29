package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val WORKSPACE_SERVER_ID = "workspace"

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

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
    val ws = repository.getById(workspaceId)
    if (ws == null || ws.shellStatus != WorkspaceShellStatus.READY.name) {
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
}.getOrElse { fail(it.message ?: "error") }


private fun parseArgs(argumentsJson: String): Map<String, String> {
    if (argumentsJson.isBlank()) return emptyMap()
    val obj = AppJson.parseObject(argumentsJson)
    return obj.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: v.toString() }
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

private fun workspaceRelativePath(path: String): String = when {
    path == "/workspace" -> ""
    path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
    else -> path.removePrefix("/")
}

private suspend fun readFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val bytes = repository.readRootfsBytes(workspaceId, path, MAX_READ_FILE_BYTES)
    return ok(
        buildString {
            append("{\"path\":\"").append(path.replace("\"", "\\\"")).append("\",\"text\":")
            append(jsonString(String(bytes, Charsets.UTF_8)))
            append('}')
        }
    )
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
        "{\"path\":\"${entry.path}\",\"sizeBytes\":${entry.sizeBytes},\"updatedAt\":${entry.updatedAt}}"
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

    val original = repository.readTextInRootfs(workspaceId, path)
    val result = runCatching { replaceText(original, oldText, newText, replaceAll) }
        .getOrElse { error("${it.message} (path: $path)") }
    val entry = repository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
    return ok(
        buildString {
            append("{\"path\":\"").append(entry.path).append("\",\"replacements\":").append(result.replacements)
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
    val cwd = (args["cwd"] ?: defaultCwd.orEmpty())
        .removePrefix("/workspace/")
        .removePrefix("/workspace")
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

private suspend fun entryDir(workspaceId: String, repository: WorkspaceRepository, area: String?): java.io.File {
    val ws = repository.getById(workspaceId) ?: error("workspace not found")
    val dir = if (area == "linux") repository.manager.linuxDir(ws.root) else repository.manager.filesDir(ws.root)
    return dir
}

private suspend fun listFiles(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val area = args["area"] ?: "files"
    val dir = entryDir(workspaceId, repository, area)
    val relativePath = when {
        area == "files" && (path == "/workspace" || path.startsWith("/workspace/")) ->
            path.removePrefix("/workspace").trimStart('/')
        area == "linux" && path == "/" -> ""
        else -> path.removePrefix("/")
    }
    val target = if (relativePath.isBlank()) dir else java.io.File(dir, relativePath)
    if (!target.isDirectory) return fail("not a directory: $path")
    val entries = target.listFiles()?.sortedBy { it.name } ?: emptyList()
    val sb = StringBuilder()
    sb.append(entries.size).append(" entr").append(if (entries.size == 1) "y" else "ies").append(" in $path\n")
    entries.forEach { f ->
        val type = if (f.isDirectory) "dir " else "file"
        sb.append(type).append(' ').append(f.name)
        if (f.isFile) sb.append("  (").append(f.length()).append(" bytes)")
        sb.append('\n')
    }
    return ok(sb.toString())
}

private suspend fun deleteFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val area = args["area"] ?: "files"
    val recursive = args["recursive"]?.toBooleanStrictOrNull() ?: false
    val dir = entryDir(workspaceId, repository, area)
    val target = java.io.File(dir, workspaceRelativePath(path))
    if (!target.exists()) return fail("not found: $path")
    if (target.isDirectory && !recursive && target.listFiles()?.isNotEmpty() == true) {
        return fail("directory not empty; pass recursive=true")
    }
    val okDel = target.deleteRecursively()
    return if (okDel) ok("deleted $path") else fail("delete failed: $path")
}

private suspend fun moveFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val src = args.absolutePath("source")
    val dst = args.absolutePath("target")
    val overwrite = args["overwrite"]?.toBooleanStrictOrNull() ?: false
    val dir = entryDir(workspaceId, repository, "files")
    val from = java.io.File(dir, workspaceRelativePath(src))
    val to = java.io.File(dir, workspaceRelativePath(dst))
    if (!from.exists()) return fail("not found: $src")
    if (to.exists() && !overwrite) return fail("target exists: $dst (pass overwrite=true)")
    if (from.renameTo(to)) return ok("moved $src -> $dst")
    // renameTo跨文件系统可能失败→copy+delete兜底
    if (from.isDirectory) return fail("move directory failed; use shell mv")
    to.parentFile?.mkdirs()
    from.copyTo(to, overwrite = true)
    from.delete()
    return ok("moved $src -> $dst (copy fallback)")
}

private suspend fun copyFile(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val src = args.absolutePath("source")
    val dst = args.absolutePath("target")
    val overwrite = args["overwrite"]?.toBooleanStrictOrNull() ?: false
    val dir = entryDir(workspaceId, repository, "files")
    val from = java.io.File(dir, workspaceRelativePath(src))
    val to = java.io.File(dir, workspaceRelativePath(dst))
    if (!from.isFile) return fail("not a file: $src")
    if (to.exists() && !overwrite) return fail("target exists: $dst (pass overwrite=true)")
    to.parentFile?.mkdirs()
    from.copyTo(to, overwrite = true)
    return ok("copied $src -> $dst (${to.length()} bytes)")
}

private suspend fun mkdir(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val path = args.absolutePath("path")
    val dir = entryDir(workspaceId, repository, "files")
    val target = java.io.File(dir, workspaceRelativePath(path))
    if (target.isDirectory) return ok("already exists: $path")
    val made = target.mkdirs()
    return if (made || target.isDirectory) ok("created $path") else fail("mkdir failed: $path")
}

private suspend fun glob(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val pattern = args.requireArg("pattern")
    val base = args["path"]?.let(::workspaceRelativePath) ?: ""
    val dir = entryDir(workspaceId, repository, "files")
    val rootDir = if (base.isBlank()) dir else java.io.File(dir, base)
    if (!rootDir.isDirectory) return fail("not a directory: ${base.ifBlank { "/" }}")
    // 简化glob：双星跨任意深度、单星只跨一层；转正则
    val regex = buildString {
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                    append(".*"); i += 2
                    if (i < pattern.length && pattern[i] == '/') i++ // 双星后面那个斜杠已被上面追加的点星吃掉
                } else { append("[^/]*"); i++ }
                '?' -> { append("[^/]"); i++ }
                else -> { if ("\\.[]{}()^$+".contains(c)) append('\\'); append(c); i++ }
            }
        }
    }
    val re = Regex(regex)
    val relRoot = rootDir.relativeTo(dir).invariantSeparatorsPath
    val out = mutableListOf<String>()
    dir.walkTopDown()
        .filter { it.isFile }
        .forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            val matchTarget = if (rel.startsWith(relRoot) && relRoot.isNotBlank()) rel.removePrefix("$relRoot/") else rel
            if (out.size < 200 && re.matches(matchTarget)) out.add(rel)
        }
    return ok(buildString {
        append(out.size).append(" match(es) for \"$pattern\"\n")
        out.forEach { append(it).append('\n') }
    })
}

private suspend fun grep(
    args: Map<String, String>,
    workspaceId: String,
    repository: WorkspaceRepository,
): McpToolResult {
    val pattern = args.requireArg("pattern")
    val base = args["path"]?.let(::workspaceRelativePath) ?: ""
    val useRegex = args["regex"]?.toBooleanStrictOrNull() ?: false
    val max = args["max_results"]?.toIntOrNull() ?: 100
    val dir = entryDir(workspaceId, repository, "files")
    val rootDir = java.io.File(dir, base)
    if (!rootDir.isDirectory) return fail("not a directory: ${base.ifBlank { "/" }}")
    val re = if (useRegex) runCatching { Regex(pattern) }.getOrElse { return fail("invalid regex: ${it.message}") } else null
    val out = mutableListOf<String>()
    rootDir.walkTopDown()
        .filter { it.isFile && it.length() < 1L * 1024 * 1024 } // 1MB以内文本
        .forEach { f ->
            if (out.size >= max) return@forEach
            runCatching {
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (out.size >= max) return@forEachIndexed
                        val hit = if (re != null) re.containsMatchIn(line) else line.contains(pattern, ignoreCase = true)
                        if (hit) out.add("${f.relativeTo(dir).invariantSeparatorsPath}:${idx + 1}: ${line.take(200)}")
                    }
                }
            }
        }
    return ok(buildString {
        append(out.size).append(" match(es) for \"$pattern\"\n")
        out.forEach { append(it).append('\n') }
    })
}

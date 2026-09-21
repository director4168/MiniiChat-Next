package com.miniichatNext.carter.data.workspace

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

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
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
)

fun isWorkspaceTool(name: String): Boolean = workspaceToolSpecs().any { it.name == name }

suspend fun executeWorkspaceTool(
    toolName: String,
    argumentsJson: String,
    workspaceId: String,
    repository: WorkspaceRepository,
    defaultCwd: String? = null,
): McpToolResult = runCatching {
    val args = parseArgs(argumentsJson)
    when (toolName) {
        "workspace_read_file" -> readFile(args, workspaceId, repository)
        "workspace_write_file" -> writeFile(args, workspaceId, repository)
        "workspace_edit_file" -> editFile(args, workspaceId, repository)
        "workspace_shell" -> shell(args, workspaceId, repository, defaultCwd)
        else -> fail("unknown workspace tool: $toolName")
    }
}.getOrElse { fail(it.message ?: "error") }

private val TOOL_JSON = Json { ignoreUnknownKeys = true }

private fun parseArgs(argumentsJson: String): Map<String, String> {
    if (argumentsJson.isBlank()) return emptyMap()
    val obj = runCatching {
        TOOL_JSON.parseToJsonElement(argumentsJson).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }
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

package com.miniichatNext.carter.data.mcp

import android.content.Context
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.data.workspace.readResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 内置工具集：文件读写/移动/复制/删除+本地shell执行+需求澄清。
 * 路径限制在允许的根目录内（外部存储+应用私有目录）。
 */
object BuiltInFileTools {

    private val json get() = AppJson.json

    private var cachedRoots: List<File>? = null
    private var appFilesDir: File? = null
    private var appExternalFilesDir: File? = null

    fun init(filesDir: File, externalFilesDir: File? = null) {
        appFilesDir = filesDir
        appExternalFilesDir = externalFilesDir
        cachedRoots = null
    }

    private fun arg(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    private fun requireArg(args: JsonObject, key: String): String =
        arg(args, key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing argument: $key")

    private fun allowedRoots(): List<File> = cachedRoots ?: buildList {
        add(File("/storage/emulated/0"))
        appFilesDir?.let { add(it) }
        appExternalFilesDir?.let { add(it) }
        runCatching { add(File("/sdcard")) }
    }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .distinctBy { it.path }
        .also { cachedRoots = it }

    private fun safeFile(rawPath: String): File = resolveToolPath(rawPath, allowedRoots())

    /** 工具名列表（给权限页计数、isBuiltIn等无文案场景用） */
    val toolNames: List<String> = listOf(
        "interactive_clarification", "execute_shell", "browser_fetch",
        "file_list", "file_read", "file_write", "file_append", "file_delete",
        "file_move", "file_copy", "file_rename", "file_mkdir",
        "file_exists", "file_search", "file_info", "file_edit"
    )

    /** 工具描述与JSON Schema走资源（内置工具页要跟随界面语言） */
    fun schema(context: Context): List<McpTool> = listOf(
        tool(
            "interactive_clarification",
            context.getString(R.string.builtin_tool_clarification_desc),
            context.getString(R.string.builtin_tool_clarification_schema)
        ),
        tool(
            "browser_fetch",
            context.getString(R.string.builtin_tool_browser_desc, BrowserTools.currentUa(context)),
            context.getString(R.string.builtin_tool_browser_schema)
        ),
        tool(
            "execute_shell",
            context.getString(R.string.builtin_tool_shell_desc),
            context.getString(R.string.builtin_tool_shell_schema)
        ),
        tool(
            "file_list",
            context.getString(R.string.builtin_tool_file_list_desc),
            context.getString(R.string.builtin_tool_file_list_schema)
        ),
        tool(
            "file_read",
            context.getString(R.string.builtin_tool_file_read_desc),
            context.getString(R.string.builtin_tool_file_read_schema)
        ),
        tool(
            "file_write",
            context.getString(R.string.builtin_tool_file_write_desc),
            context.getString(R.string.builtin_tool_file_write_schema)
        ),
        tool(
            "file_append",
            context.getString(R.string.builtin_tool_file_append_desc),
            context.getString(R.string.builtin_tool_file_append_schema)
        ),
        tool(
            "file_delete",
            context.getString(R.string.builtin_tool_file_delete_desc),
            context.getString(R.string.builtin_tool_file_delete_schema)
        ),
        tool(
            "file_move",
            context.getString(R.string.builtin_tool_file_move_desc),
            context.getString(R.string.builtin_tool_file_move_schema)
        ),
        tool(
            "file_copy",
            context.getString(R.string.builtin_tool_file_copy_desc),
            context.getString(R.string.builtin_tool_file_copy_schema)
        ),
        tool(
            "file_rename",
            context.getString(R.string.builtin_tool_file_rename_desc),
            context.getString(R.string.builtin_tool_file_rename_schema)
        ),
        tool(
            "file_mkdir",
            context.getString(R.string.builtin_tool_file_mkdir_desc),
            context.getString(R.string.builtin_tool_file_mkdir_schema)
        ),
        tool(
            "file_exists",
            context.getString(R.string.builtin_tool_file_exists_desc),
            context.getString(R.string.builtin_tool_file_exists_schema)
        ),
        tool(
            "file_search",
            context.getString(R.string.builtin_tool_file_search_desc),
            context.getString(R.string.builtin_tool_file_search_schema)
        ),
        tool(
            "file_edit",
            context.getString(R.string.builtin_tool_file_edit_desc),
            context.getString(R.string.builtin_tool_file_edit_schema)
        ),
        tool(
            "file_info",
            context.getString(R.string.builtin_tool_file_info_desc),
            context.getString(R.string.builtin_tool_file_info_schema)
        )
    )
    private fun tool(name: String, desc: String, schema: String) =
        McpTool(name = name, description = desc, inputSchema = schema, serverId = "", builtIn = true)

    fun isBuiltIn(name: String): Boolean = toolNames.contains(name)

    /** 默认免审批的工具（询问类工具不阻塞，用户在选项卡片上交互） */
    /** 默认免审批：询问类工具+只读网络工具（browser_fetch）不阻塞 */
    fun defaultRequireApproval(name: String): Boolean =
        name != "interactive_clarification" && name != "browser_fetch"
    fun hasTool(name: String): Boolean = isBuiltIn(name)

    fun execute(context: Context, name: String, argumentsJson: String): McpToolResult = runCatching {
        val args = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else json.parseToJsonElement(argumentsJson).jsonObject
        when (name) {
            "interactive_clarification" -> interactiveClarification(context, args)
            "browser_fetch" -> BrowserTools.execute(context, args)
            "execute_shell" -> executeShell(context, args)
            "file_list" -> fileList(args)
            "file_read" -> fileRead(args)
            "file_write" -> fileWrite(args, append = false)
            "file_append" -> fileWrite(args, append = true)
            "file_delete" -> fileDelete(args)
            "file_move" -> fileMoveOrCopy(args, copy = false)
            "file_copy" -> fileMoveOrCopy(args, copy = true)
            "file_rename" -> fileRename(args)
            "file_mkdir" -> fileMkdir(args)
            "file_exists" -> fileExists(args)
            "file_search" -> fileSearch(args)
            "file_edit" -> fileEdit(args)
            "file_info" -> fileInfo(args)
            else -> McpToolResult(ok = false, content = "", error = "unknown builtin tool: $name")
        }
    }.getOrElse { e ->
        if (e is InterruptedException) throw e
        DebugLog.e("BuiltInTools", "execute $name failed: ${e.message}", e)
        val error = if (e.message?.startsWith("path is outside allowed roots:") == true) {
            context.getString(R.string.builtin_path_outside_roots)
        } else e.message ?: "error"
        McpToolResult(ok = false, content = "", error = error)
    }

    private fun executeShell(context: Context, args: JsonObject): McpToolResult {
        val script = requireArg(args, "script")
        val timeoutSec = (arg(args, "timeout_seconds")?.toLongOrNull() ?: 30L).coerceIn(1L, 600L)
        val tmpDir = File(appFilesDir ?: File("/data/local/tmp"), "scripts").apply { mkdirs() }
        val scriptFile = File.createTempFile("run_", ".sh", tmpDir)
        return try {
            scriptFile.writeText(script)
            val process = ProcessBuilder("sh", scriptFile.absolutePath)
                .directory(appFilesDir ?: File("/"))
                .redirectErrorStream(true)
                .start()
            val result = process.readResult(TimeUnit.SECONDS.toMillis(timeoutSec))
            if (result.timedOut) return fail(context.getString(R.string.builtin_shell_timeout, timeoutSec))
            val output = result.stdout.trimFragment(MAX_TOOL_OUTPUT_CHARS)
            if (result.exitCode == 0) ok(output.ifBlank { context.getString(R.string.builtin_shell_no_output) })
            else fail(context.getString(R.string.builtin_shell_exit, result.exitCode, output))
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            fail(context.getString(R.string.builtin_shell_failed, e.message.orEmpty()))
        } finally {
            runCatching { scriptFile.delete() }
        }
    }

    private fun interactiveClarification(context: Context, args: JsonObject): McpToolResult {
        val question = arg(args, "question") ?: context.getString(R.string.builtin_clarify_default_question)
        val rawOptions = args["options"]?.jsonArray?.mapNotNull { el ->
            when (el) {
                is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull
                is JsonObject -> {
                    (listOf("content", "item", "value", "text", "label", "choice")
                        .firstNotNullOfOrNull { key -> (el[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull })
                        ?: el.values
                            .filterIsInstance<kotlinx.serialization.json.JsonPrimitive>()
                            .firstOrNull { it.contentOrNull?.isNotBlank() == true }?.contentOrNull
                }
                else -> null
            }
        } ?: emptyList()
        val options = rawOptions.take(4)
        val allowMultiple = args["allow_multiple"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        // 结果只做简短确认——不含选项枚举：选项全文会以"工具结果"展示在COT卡片上，
        // 用户提交选择后卡片result会覆盖为所选；枚举文本只会让用户误以为选项被重复提交
        return ok(context.getString(R.string.builtin_clarify_shown))
    }

    private fun fileList(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("not found: ${f.path}")
        if (!f.isDirectory) return fail("not a directory: ${f.path}")
        val children = f.listFiles()?.sortedBy { it.name } ?: emptyList()
        val sb = StringBuilder()
        sb.append("directory: ").append(f.path).append('\n')
        children.forEach { c ->
            val type = if (c.isDirectory) "DIR " else "FILE"
            val size = if (c.isDirectory) "-" else c.length().toString()
            sb.append(type).append('\t').append(size).append('\t').append(c.name).append('\n')
        }
        if (children.isEmpty()) sb.append("(empty)\n")
        return ok(sb.toString())
    }

    private fun fileRead(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("not found: ${f.path}")
        if (!f.isFile) return fail("not a file: ${f.path}")
        val max = arg(args, "max_bytes")?.toIntOrNull() ?: DEFAULT_MAX_READ_BYTES
        val size = f.length()
        if (size > max) {
            return fail(
                "file too large (${formatBytes(size)}, max ${formatBytes(max.toLong())}). " +
                    "Read parts with execute_shell (head / tail / grep), or pass a larger max_bytes."
            )
        }
        return ok(String(f.readBytes(), Charsets.UTF_8))
    }

    private fun fileWrite(args: JsonObject, append: Boolean): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val content = arg(args, "content") ?: ""
        f.parentFile?.mkdirs()
        if (append) f.appendText(content) else f.writeText(content)
        return ok("${if (append) "appended" else "written"} ${content.length} chars to ${f.path}")
    }

    private fun fileDelete(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("not found: ${f.path}")
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (deleted) ok("deleted: ${f.path}") else fail("delete failed: ${f.path}")
    }

    private fun fileMoveOrCopy(args: JsonObject, copy: Boolean): McpToolResult {
        val from = safeFile(requireArg(args, "from"))
        val to = safeFile(requireArg(args, "to"))
        if (!from.exists()) return fail("source not found: ${from.path}")
        val target = if (to.isDirectory) File(to, from.name) else to
        target.parentFile?.mkdirs()
        return if (copy) {
            if (from.isDirectory) from.copyRecursively(target, overwrite = true)
            else from.copyTo(target, overwrite = true)
            ok("copied ${from.path} -> ${target.path}")
        } else {
            if (from.isDirectory) {
                if (!from.renameTo(target)) {
                    from.copyRecursively(target, overwrite = true)
                    from.deleteRecursively()
                }
            } else {
                if (!from.renameTo(target)) {
                    from.copyTo(target, overwrite = true)
                    from.delete()
                }
            }
            ok("moved ${from.path} -> ${target.path}")
        }
    }

    private fun fileRename(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val newName = requireArg(args, "new_name")
        if (!f.exists()) return fail("not found: ${f.path}")
        val target = runCatching { resolveRenameTarget(f, newName, allowedRoots()) }
            .getOrElse { return fail(it.message ?: "invalid rename target") }
        val done = f.renameTo(target)
        return if (done) ok("renamed ${f.name} -> $newName") else fail("rename failed")
    }

    private fun fileMkdir(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val done = if (f.exists()) f.isDirectory else f.mkdirs()
        return if (done) ok("directory ready: ${f.path}") else fail("mkdir failed: ${f.path}")
    }

    private fun fileExists(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val desc = when {
            !f.exists() -> "not exists"
            f.isDirectory -> "directory"
            f.isFile -> "file"
            else -> "unknown"
        }
        return ok("${f.path}: $desc")
    }

    private fun fileSearch(args: JsonObject): McpToolResult {
        val root = safeFile(requireArg(args, "path"))
        val keyword = requireArg(args, "keyword")
        val useRegex = arg(args, "regex")?.toBooleanStrictOrNull() ?: false
        val max = arg(args, "max_results")?.toIntOrNull() ?: 50
        if (!root.isDirectory) return fail("not a directory: ${root.path}")
        val regex = if (useRegex) {
            runCatching { Regex(keyword, RegexOption.IGNORE_CASE) }.getOrElse {
                return fail("invalid regex: ${it.message}")
            }
        } else null
        val matcher: (String) -> Boolean = { name ->
            if (regex != null) regex.containsMatchIn(name)
            else name.contains(keyword, ignoreCase = true)
        }
        val out = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (out.size >= max) return@forEach
            if (matcher(f.name)) out.add(f.path)
        }
        val mode = if (useRegex) "regex" else "keyword"
        val sb = StringBuilder()
        sb.append("found ${out.size} match(es) for \"$keyword\" ($mode) under ${root.path}\n")
        out.forEach { sb.append(it).append('\n') }
        return ok(sb.toString())
    }

    /**
     * 精确编辑（参考rikkahub TextReplacers三级回退）：
     * 1) exact——精确子串匹配（非重叠）
     * 2) line_trimmed——逐行trim后比较（容忍缩进/行尾空白/CRLF）
     * 3) block_anchor——首尾两行锚定+中间任意（大块替换）
     * replace_all=false时要求恰好1处命中（多处报错让模型缩小上下文）
     */
    private fun fileEdit(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        val oldText = requireArg(args, "old_text")
        val newText = arg(args, "new_text") ?: ""
        val replaceAll = arg(args, "replace_all")?.toBooleanStrictOrNull() ?: false
        if (!f.isFile) return fail("not a file: ${f.path}")
        val content = runCatching { f.readText() }.getOrElse { return fail("read failed: ${it.message}") }
        val plan = planTextEdit(content, oldText, newText, replaceAll)
        plan.error?.let { return fail(it) }
        if (plan.matches.isEmpty()) return fail("old_text produced no edit")
        val edited = StringBuilder(content)
        plan.matches.asReversed().forEach { match ->
            edited.replace(match.start, match.endExclusive, match.replacement)
        }
        return runCatching {
            f.writeText(edited.toString())
            ok("edited ${plan.matches.size} location(s) in ${f.path}")
        }.getOrElse { fail("write failed: ${it.message}") }
    }

    private fun fileInfo(args: JsonObject): McpToolResult {
        val f = safeFile(requireArg(args, "path"))
        if (!f.exists()) return fail("not found: ${f.path}")
        val sb = StringBuilder()
        sb.append("path: ").append(f.path).append('\n')
        sb.append("type: ").append(if (f.isDirectory) "directory" else "file").append('\n')
        sb.append("size: ").append(if (f.isDirectory) "-" else f.length().toString()).append('\n')
        sb.append("lastModified: ").append(f.lastModified()).append('\n')
        sb.append("readable: ").append(f.canRead()).append('\n')
        sb.append("writable: ").append(f.canWrite()).append('\n')
        return ok(sb.toString())
    }

    private fun ok(text: String) = McpToolResult(ok = true, content = text)
    private fun fail(msg: String) = McpToolResult(ok = false, content = "", error = msg)

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "%.1fKB".format(b / 1024.0)
        else -> "%.1fMB".format(b / 1024.0 / 1024.0)
    }

    // 工具返回String过长时截断，避免撑爆模型上下文（保留主体+加标记）
    private fun String.trimFragment(maxChars: Int): String =
        if (length <= maxChars) this
        else take(maxChars * 3 / 4) + "\n...[truncated ${length - maxChars * 3 / 4} chars]..."
}

const val DEFAULT_MAX_READ_BYTES = 256 * 1024
const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024

package com.miniichatNext.carter.data.mcp

import com.miniichatNext.carter.debug.DebugLog
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
 * 内置工具集：文件读写/移动/复制/删除 + 本地shell执行 + 需求澄清。
 * 路径限制在允许的根目录内（外部存储 + 应用私有目录）。
 */
object BuiltInFileTools {

    private val json = Json { ignoreUnknownKeys = true }

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

    private fun safeFile(rawPath: String): File {
        val trimmed = rawPath.trim()
        val targetPath = when {
            trimmed.isEmpty() || trimmed == "/" || trimmed == "." || trimmed == "./" -> "/storage/emulated/0"
            trimmed.startsWith("/tmp") -> "/storage/emulated/0/Download" + trimmed.removePrefix("/tmp")
            !trimmed.startsWith("/") -> "/storage/emulated/0/$trimmed"
            else -> trimmed
        }
        val f = File(targetPath).canonicalFile
        val ok = allowedRoots().any { root ->
            f.path == root.path || f.path.startsWith(root.path + File.separator)
        }
        if (!ok) {
            return File("/storage/emulated/0/Download", f.name.ifBlank { "mcp_workspace" }).canonicalFile
        }
        return f
    }

    val schema: List<McpTool> = listOf(
        tool(
            "interactive_clarification",
            "当需要确认或进一步理解用户的需求、偏好或方案时调用此工具。向用户抛出题目与最多4个预设选项，用户可单选、多选或在末尾自定义输入补充信息。",
            """{"type":"object","properties":{"question":{"type":"string","description":"需要向用户确认的问题或题目"},"options":{"type":"array","items":{"type":"string"},"description":"选项列表，最多4个具体选项"},"allow_multiple":{"type":"boolean","description":"是否允许多选"}},"required":["question","options"]}"""
        ),
        tool(
            "execute_shell",
            "在安卓本机环境中执行 Shell (sh/bash) 脚本或终端命令，返回标准输出与错误输出。",
            """{"type":"object","properties":{"script":{"type":"string","description":"要执行的 Shell 脚本代码或命令行"},"timeout_seconds":{"type":"integer","description":"执行超时时间（秒），默认 30 秒"}},"required":["script"]}"""
        ),
        tool(
            "file_list",
            "列出指定目录下的文件与子目录。返回名称、类型、大小。",
            """{"type":"object","properties":{"path":{"type":"string","description":"目录绝对路径，例如 /storage/emulated/0"}},"required":["path"]}"""
        ),
        tool(
            "file_read",
            "读取文本文件内容。可指定最大读取字节数，默认 256KB。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件绝对路径"},"max_bytes":{"type":"integer","description":"最大读取字节数"}},"required":["path"]}"""
        ),
        tool(
            "file_write",
            "把文本内容写入文件（覆盖）。父目录不存在会自动创建。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件绝对路径"},"content":{"type":"string","description":"写入内容"}},"required":["path","content"]}"""
        ),
        tool(
            "file_append",
            "把文本内容追加到文件末尾。文件不存在会创建。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件绝对路径"},"content":{"type":"string","description":"追加内容"}},"required":["path","content"]}"""
        ),
        tool(
            "file_delete",
            "删除文件或目录。目录会递归删除。",
            """{"type":"object","properties":{"path":{"type":"string","description":"要删除的文件或目录绝对路径"}},"required":["path"]}"""
        ),
        tool(
            "file_move",
            "把文件或目录移动到新位置。",
            """{"type":"object","properties":{"from":{"type":"string","description":"源路径"},"to":{"type":"string","description":"目标路径"}},"required":["from","to"]}"""
        ),
        tool(
            "file_copy",
            "复制文件或目录到新位置。",
            """{"type":"object","properties":{"from":{"type":"string","description":"源路径"},"to":{"type":"string","description":"目标路径"}},"required":["from","to"]}"""
        ),
        tool(
            "file_rename",
            "重命名文件或目录（同一目录内的改名）。",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件绝对路径"},"new_name":{"type":"string","description":"新名称"}},"required":["path","new_name"]}"""
        ),
        tool(
            "file_mkdir",
            "创建目录（含多级父目录）。",
            """{"type":"object","properties":{"path":{"type":"string","description":"目录绝对路径"}},"required":["path"]}"""
        ),
        tool(
            "file_exists",
            "判断路径是否存在，并返回是文件还是目录。",
            """{"type":"object","properties":{"path":{"type":"string","description":"路径"}},"required":["path"]}"""
        ),
        tool(
            "file_search",
            "在指定目录下按文件名关键字递归搜索，返回匹配的路径列表。",
            """{"type":"object","properties":{"path":{"type":"string","description":"搜索根目录"},"keyword":{"type":"string","description":"搜索关键字"},"max_results":{"type":"integer","description":"最大匹配数量"}},"required":["path","keyword"]}"""
        ),
        tool(
            "file_info",
            "获取文件/目录的详细信息：大小、最后修改时间、是否目录。",
            """{"type":"object","properties":{"path":{"type":"string","description":"路径"}},"required":["path"]}"""
        )
    )

    private fun tool(name: String, desc: String, schema: String) =
        McpTool(name = name, description = desc, inputSchema = schema, serverId = "", builtIn = true)

    fun isBuiltIn(name: String): Boolean = schema.any { it.name == name }
    fun hasTool(name: String): Boolean = isBuiltIn(name)

    fun execute(name: String, argumentsJson: String): McpToolResult = runCatching {
        val args = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else json.parseToJsonElement(argumentsJson).jsonObject
        when (name) {
            "interactive_clarification" -> interactiveClarification(args)
            "execute_shell" -> executeShell(args)
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
            "file_info" -> fileInfo(args)
            else -> McpToolResult(ok = false, content = "", error = "unknown builtin tool: $name")
        }
    }.getOrElse { e ->
        DebugLog.e("BuiltInTools", "execute $name failed: ${e.message}", e)
        McpToolResult(ok = false, content = "", error = e.message ?: "error")
    }

    private fun executeShell(args: JsonObject): McpToolResult {
        val script = requireArg(args, "script")
        val timeoutSec = arg(args, "timeout_seconds")?.toLongOrNull() ?: 30L
        val tmpDir = File(appFilesDir ?: File("/data/local/tmp"), "scripts").apply { mkdirs() }
        val scriptFile = File(tmpDir, "run_${System.currentTimeMillis()}.sh")
        return try {
            scriptFile.writeText(script)
            scriptFile.setExecutable(true)
            val pb = ProcessBuilder("sh", scriptFile.absolutePath)
            pb.directory(appFilesDir ?: File("/"))
            pb.redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return fail("执行超时（超过 $timeoutSec 秒）")
            }
            val raw = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.exitValue()
            // 工具输出上限（对齐rikkahub）：避免撑爆模型上下文
            val output = raw.trimFragment(MAX_TOOL_OUTPUT_CHARS)
            if (exitCode == 0) ok(output.ifBlank { "(执行成功，无输出)" })
            else fail("执行退出代码: $exitCode\n输出:\n$output")
        } catch (e: Exception) {
            fail("Shell 执行失败: ${e.message}")
        } finally {
            runCatching { scriptFile.delete() }
        }
    }

    private fun interactiveClarification(args: JsonObject): McpToolResult {
        val question = arg(args, "question") ?: "需求选项确认"
        val rawOptions = args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val options = rawOptions.take(4)
        val allowMultiple = args["allow_multiple"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val sb = StringBuilder()
        sb.append("已向用户展示选项卡片并收集反馈：\n")
        sb.append("问题：").append(question).append('\n')
        sb.append("模式：").append(if (allowMultiple) "多选" else "单选").append('\n')
        options.forEachIndexed { i, opt -> sb.append("选项").append(i + 1).append("：").append(opt).append('\n') }
        sb.append("末尾选项：其他（支持用户手动输入自定义需求）\n")
        return ok(sb.toString())
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
        val target = File(f.parentFile, newName)
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
        val max = arg(args, "max_results")?.toIntOrNull() ?: 50
        if (!root.isDirectory) return fail("not a directory: ${root.path}")
        val out = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (out.size >= max) return@forEach
            if (f.name.contains(keyword, ignoreCase = true)) out.add(f.path)
        }
        val sb = StringBuilder()
        sb.append("found ${out.size} match(es) for \"$keyword\" under ${root.path}\n")
        out.forEach { sb.append(it).append('\n') }
        return ok(sb.toString())
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

    // 工具返回String过长时截断，避免撑爆模型上下文（保留主体 + 加标记）
    private fun String.trimFragment(maxChars: Int): String =
        if (length <= maxChars) this
        else take(maxChars * 3 / 4) + "\n...[truncated ${length - maxChars * 3 / 4} chars]..."
}

const val DEFAULT_MAX_READ_BYTES = 256 * 1024
const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024

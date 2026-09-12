package com.miniichatNext.carter.Debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DebugLevel(val label: String) {
    VERBOSE("VERBOSE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR");

    companion object {
        fun from(name: String?): DebugLevel = entries.firstOrNull { it.name == name } ?: WARN
    }
}

/** 日志打包时的一个待写入条目 */
private data class ZipSource(
    val file: File,
    val entryName: String,
    /** 打包成功后可以删除：旧的崩溃归档 zip（内容已经进导出包了，留着只会堆积） */
    val deleteAfterPack: Boolean
)

object DebugLog {
    private const val DIR = "logs"
    private const val MAX_BUFFER = 600
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MANIFEST_NAME = "manifest.txt"

    /** 导出 zip 里额外附带的内存缓冲快照文件名（放在 logs/ 下，前缀下划线表示是合成文件） */
    private const val SESSION_BUFFER_NAME = "_session_buffer.log"
    private const val SESSION_BUFFER_HEADER =
        "# In-memory session buffer (up to 600 recent lines).\n" +
            "# Lines below the active log level are kept here only (never written to disk),\n" +
            "# so this file may overlap with logs/DEBUG_*.log.\n"

    private val mutex = Mutex()

    private val fileLock = Any()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "DebugLog-IO") }

    /** 统一的导出文件名：MiniiChat-Next_Log_<yyyy-MM-dd_HH-mm-ss>.zip（下载目录与缓存目录共用） */
    private fun exportFileName(): String =
        "MiniiChat-Next_Log_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".zip"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    // 默认WARN
    var level: DebugLevel = DebugLevel.WARN
        private set

    // ---------- lifecycle ----------

    fun restore(context: Context) {
        appContext = context.applicationContext
        level = DebugLevel.from(
            context.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
                .getString("level", null)
        )
    }

    fun setLevel(context: Context, lvl: DebugLevel) {
        appContext = context.applicationContext
        level = lvl
        context.getSharedPreferences("debug_prefs", Context.MODE_PRIVATE)
            .edit().putString("level", lvl.name).apply()
        i("DebugLog", "log level changed to " + lvl.name)
    }

    // ---------- paths ----------

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun logFile(context: Context, lvl: DebugLevel = level): File =
        File(dir(context), "DEBUG_" + lvl.name + ".log")

    private fun logFileSafe(): File? = appContext?.let { logFile(it) }

    // ---------- logging ----------

    fun v(tag: String, msg: String) = log(DebugLevel.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(DebugLevel.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(DebugLevel.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = log(DebugLevel.WARN, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(DebugLevel.ERROR, tag, msg, tr)

    private fun log(lvl: DebugLevel, tag: String, msg: String, tr: Throwable?) {
        val text = buildString {
            append(msg)
            if (tr != null) {
                append('\n')
                append(StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString())
            }
        }
        val line = timeFormat.format(Date()) + " [" + lvl.name + "/" + tag + "] " + text
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        if (lvl.ordinal >= level.ordinal) {
            io.execute { runCatching { appendToDisk(line) } }
        }
    }

    private fun appendToDisk(line: String) {
        val f = logFileSafe() ?: return
        synchronized(fileLock) {
            runCatching {
                // 超过上限就清空重来，避免日志无限膨胀
                if (f.length() > MAX_FILE_BYTES) f.writeText("")
                f.appendText(line + "\n")
            }
        }
    }

    // ---------- read / export ----------

    fun bufferText(): String = synchronized(buffer) { buffer.joinToString("\n") }

    // 崩溃路径必须用这个：进程马上就会死，走io executor的异步写入可能来不及落盘

    fun appendDirectSync(context: Context, levelName: String, tag: String, text: String) {
        runCatching {
            val f = logFile(context)
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(
                timeFormat.format(Date()) + " " + levelName + "/" + tag + " " + text + "\n"
            )
        }
    }

    suspend fun readText(context: Context, maxBytes: Int = 256 * 1024): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = logFile(context)
                // 先加锁把内容快照下来，避免和 appendToDisk 争先读到写了一半的行
                val snapshot = synchronized(fileLock) {
                    runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()
                }
                val disk = snapshot?.let { bytes ->
                    if (bytes.size > maxBytes) {
                        "> (日志过长，仅显示末尾部分)\n" +
                            String(bytes, bytes.size - maxBytes, maxBytes)
                    } else {
                        String(bytes)
                    }
                } ?: ""
                disk.ifBlank { bufferText() }
            }
        }

    /** 打包 logs 目录全部文件到 cacheDir/shared 下的 zip（分享/邮件用，文件名与下载目录统一） */
    fun exportZip(context: Context): File {
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(shared, exportFileName())
        out.outputStream().use { writeLogZip(context, it) }
        d("DebugLog", "export zip done: " + out.absolutePath)
        return out
    }

    /**
     * 打包全部日志到**系统下载目录**（Download/）
     *
     * 先在 `cacheDir/shared` 生成完整 zip，再整体拷贝到目标位置 —— 这样即使写目标
     * 中途失败，也不会在下载目录里留下"只有前几个条目"的半包
     *
     * - API 29+：走 MediaStore.Downloads，不需要任何存储权限
     * - API <29：写公共 Download 需要 WRITE_EXTERNAL_STORAGE，失败时退到应用专属外部目录
     *
     * @return 展示用的保存位置（成功）或 null（失败）
     */
    fun exportZipToDownloads(context: Context): String? {
        val name = exportFileName()

        // 1) 先在 cacheDir 生成完整 zip（写日志 zip + 清理已归档的崩溃 zip 都在这一步完成）
        val staging = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)
        val staged = runCatching {
            staging.outputStream().use { writeLogZip(context, it) }
        }
        if (staged.isFailure) {
            e("DebugLog", "staging log zip failed", staged.exceptionOrNull())
            runCatching { staging.delete() }
            return null
        }

        // 2) 整体拷到目标位置
        val attempt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    staging.inputStream().use { it.copyTo(out) }
                } ?: error("openOutputStream returned null")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Environment.DIRECTORY_DOWNLOADS + "/" + name
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, name)
                staging.inputStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.absolutePath
            }
        }

        val result = attempt.getOrElse { e ->
            // 注意：w(tag, msg) 没有 Throwable 参数，带异常要用 e(tag, msg, tr)
            e("DebugLog", "export to Downloads failed, trying app-specific dir: ${e.message}", e)
            runCatching {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, name)
                staging.inputStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.absolutePath
            }.getOrNull()
        }
        runCatching { staging.delete() }
        return result
    }

    private fun writeLogZip(context: Context, out: OutputStream) {
        val root = dir(context)

        // 把要打包的内容先读进内存
        val payloads = LinkedHashMap<String, ByteArray>()
        val manifestLines = LinkedHashMap<String, String>()
        val deletableCrashZips = LinkedHashMap<String, File>()

        synchronized(fileLock) {
            val sources = LinkedHashMap<String, ZipSource>()

            root.walkTopDown()
                .filter { it.isFile }
                .forEach { f ->
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    val entryName = "logs/$rel"
                    sources.putIfAbsent(
                        entryName,
                        ZipSource(
                            file = f,
                            entryName = entryName,
                            deleteAfterPack = CrashLogger.isCrashZipName(f.name)
                        )
                    )
                }

            val legacyCrashDir = File(context.filesDir, "crash")
            if (legacyCrashDir.isDirectory) {
                legacyCrashDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    val entryName = "logs/crash/" + f.name
                    sources.putIfAbsent(
                        entryName,
                        ZipSource(file = f, entryName = entryName, deleteAfterPack = false)
                    )
                }
            }

            sources.values.forEach { src ->
                val bytes = runCatching { src.file.readBytes() }.getOrNull()
                if (bytes == null) {
                    w("DebugLog", "skip unreadable log file: ${src.entryName}")
                    return@forEach
                }
                payloads[src.entryName] = bytes
                manifestLines[src.entryName] = src.entryName +
                    "  bytes=" + bytes.size +
                    "  mtime=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date(src.file.lastModified()))
                if (src.deleteAfterPack) deletableCrashZips[src.entryName] = src.file
            }

            val buffered = bufferText()
            if (buffered.isNotBlank()) {
                val entryName = "logs/$SESSION_BUFFER_NAME"
                val bytes = (SESSION_BUFFER_HEADER + buffered).toByteArray(Charsets.UTF_8)
                payloads[entryName] = bytes
                manifestLines[entryName] = entryName +
                    "  bytes=" + bytes.size + "  mtime=(in-memory)"
            }
        }

        // 组装manifest，然后创建zip
        val manifest = StringBuilder()
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            manifest.append("app=").append(context.packageName)
                .append("  version=").append(info.versionName).append(" (").append(code).append(")")
                .append("  build=").append(com.miniichatNext.carter.BuildConfig.BUILD_TYPE)
                .append('\n')
                .append("android=").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                .append("  device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append('\n')
                .append("logLevel=").append(level.name)
                .append("  exportedAt=")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append('\n')
                .append("entries=").append(payloads.size + 1).append(" (including this manifest)")
                .append("\n\n")
        }
        manifestLines.values.forEach { manifest.append(it).append('\n') }

        val written = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            payloads.forEach { (entryName, bytes) ->
                val ok = runCatching {
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                    true
                }.getOrElse { e ->
                    w("DebugLog", "zip entry failed: $entryName -> ${e.message}")
                    false
                }
                if (ok) written += entryName
            }
            runCatching {
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        // 只删除写进本次 zip的崩溃归档zip
        var removed = 0
        deletableCrashZips.forEach { (entryName, file) ->
            if (entryName in written) {
                runCatching { if (file.delete()) removed++ }
            }
        }
        d(
            "DebugLog",
            "log zip written: ${written.size} entries, removed $removed packaged crash zip(s)"
        )
    }

    fun clear(context: Context) {
        io.execute {
            synchronized(fileLock) {
                runCatching {
                    // 递归删除logs/下的文件+logs/crash/子目录
                    dir(context).listFiles()?.forEach { f ->
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                }
            }
        }
        synchronized(buffer) { buffer.clear() }
    }
}

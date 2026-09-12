package com.miniichatNext.carter.Debug

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 崩溃捕获
 * 使用了我的另一个项目: CarterToolbox(https://github.com/director4168/CarterToolbox)的逻辑，也算是推广一下我的这个工具箱吧
 */
object CrashLogger {
    private const val CRASH_DIR = "crash"
    private const val LEGACY_CRASH_DIR = "crash"
    private const val LATEST_NAME = "latest_crash.log"
    private const val ZIP_PREFIX = "MiniiChat-Next_Crash_"
    private const val LEGACY_ZIP_PREFIX = "MiniiChatNext_crash_"
    private const val KEEP_CRASH_ZIPS = 3

    /** 是否是崩溃归档zip */
    internal fun isCrashZipName(name: String): Boolean =
        name.endsWith(".zip") &&
            (name.startsWith(ZIP_PREFIX) || name.startsWith(LEGACY_ZIP_PREFIX))

    fun install(context: Context) {
        val appContext = context.applicationContext
        migrateLegacyDir(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
                ?: kotlin.system.exitProcess(2)
        }
    }

    /** 崩溃日志目录：filesDir/logs/crash */
    fun crashDir(context: Context): File =
        File(DebugLog.dir(context), CRASH_DIR).apply { mkdirs() }

    fun migrateLegacyDir(context: Context) {
        runCatching {
            val legacy = File(context.filesDir, LEGACY_CRASH_DIR)
            if (!legacy.isDirectory) return@runCatching
            val target = crashDir(context)
            legacy.listFiles()?.forEach { f ->
                val dest = File(target, f.name)
                val renamed = runCatching { f.renameTo(dest) }.getOrDefault(false)
                if (renamed && dest.isFile) return@forEach
                val copied = runCatching {
                    f.copyTo(dest, overwrite = true)
                    dest.isFile
                }.getOrDefault(false)
                if (copied) runCatching { f.delete() }
                DebugLog.w(
                    "CrashLogger",
                    "migrate legacy crash file ${f.name}: rename=$renamed copied=$copied"
                )
            }
            if (legacy.listFiles().isNullOrEmpty()) legacy.delete()
        }
    }

    fun latestCrashFile(context: Context): File = File(crashDir(context), LATEST_NAME)

    fun hasCrash(context: Context): Boolean = latestCrashFile(context).isFile

    fun readLatestCrash(context: Context): String =
        latestCrashFile(context).takeIf { it.isFile }?.readText() ?: ""

    fun clearLatestCrash(context: Context): Boolean = latestCrashFile(context).delete()

    fun crashZipFile(context: Context): File {
        val source = latestCrashFile(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val out = File(crashDir(context), ZIP_PREFIX + stamp + ".zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("crash.log"))
            if (source.isFile) source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        // 每次分享/发邮件都会生成一份，不设上限会无限堆积（日志打包会收走内容后删除，但从不导出日志的用户会一直攒），所以这里保留最近KEEP_CRASH_ZIPS份
        pruneCrashZips(context, keep = out)
        return out
    }

    /** 崩溃归档zip只保留最近[KEEP_CRASH_ZIPS]份（含[keep]本身） */
    private fun pruneCrashZips(context: Context, keep: File?) {
        runCatching {
            crashDir(context).listFiles { f -> f.isFile && isCrashZipName(f.name) }
                ?.sortedByDescending { it.lastModified() }
                ?.filter { it.absolutePath != keep?.absolutePath }
                ?.drop(KEEP_CRASH_ZIPS - 1)
                ?.forEach { it.delete() }
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val appVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "unknown"} ($code)"
        }.getOrDefault("unknown")
        val text = buildString {
            appendLine("MiniiChat Next Crash Report")
            appendLine("Time: $now")
            appendLine("App Version: $appVersion")
            appendLine("Thread: ${thread.name} / ${thread.id}")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Brand/Product: ${Build.BRAND} / ${Build.PRODUCT}")
            appendLine()
            appendLine(stack)
        }
        crashDir(context).apply {
            File(this, LATEST_NAME).writeText(text)
            // 只保留最新一份latest；历史按时间戳归档，最多5份
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(this, "crash_$stamp.log").writeText(text)
            listFiles { f -> f.name.startsWith("crash_") }
                ?.sortedByDescending { it.name }
                ?.drop(5)
                ?.forEach { it.delete() }
        }
        // 同步（非异步）把崩溃报告写进当前等级的DEBUG_xxx.log：
        // 进程随即终止，异步写队列来不及执行
        DebugLog.appendDirectSync(context, "ERROR", "Crash", text)
        DebugLog.e("Crash", "uncaught exception on ${thread.name}", throwable)
    }
}

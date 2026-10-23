package com.miniichatNext.carter.data.update

import android.content.Context
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APK下载器：流式写入+进度回调+速度估算+SHA256校验+取消支持。
 *
 * 不引入额外依赖，用JDK HttpURLConnection。
 */
class UpdateDownloader(
    /** 目标APK文件路径（建议filesDir/cache/Update/miniichat.apk） */
    val targetFile: File,
) {
    /** 进度回调（totalBytes可能为-1表示服务器没给Content-Length） */
    data class Progress(
        val bytesRead: Long,
        val totalBytes: Long,
        val bytesPerSec: Long,
    )

    sealed class Result {
        data class Success(val apkFile: File) : Result()
        /**
         * @param message 错误描述
         * @param apkFile 校验失败但文件保留在磁盘→用户可点"强制安装"绕过SHA。
         * 非SHA类失败（HTTP/网络）则为null。
         */
        data class Failure(val message: String, val apkFile: File? = null) : Result()
    }

    private var job: Job? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        job?.cancel()
    }

    suspend fun download(
        context: Context,
        url: String,
        expectedSha256: String,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (cancelled) return@withContext Result.Failure(context.getString(R.string.update_cancelled))
        try {
            targetFile.parentFile?.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; MiniiChat-Next-Updater) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                instanceFollowRedirects = true
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: -1L

            val startedAt = System.currentTimeMillis()
            var lastEmitAt = startedAt
            var lastEmitBytes = 0L
            var totalRead = 0L

            conn.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) {
                            return@withContext Result.Failure(context.getString(R.string.update_cancelled))
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        totalRead += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= 300) {
                            val bytesPerSec = if (now == lastEmitAt) 0
                            else ((totalRead - lastEmitBytes) * 1000L) / (now - lastEmitAt)
                            onProgress(
                                Progress(
                                    bytesRead = totalRead,
                                    totalBytes = totalBytes,
                                    bytesPerSec = bytesPerSec,
                                )
                            )
                            lastEmitAt = now
                            lastEmitBytes = totalRead
                        }
                    }
                }
            }

            // SHA256校验（保留文件，由UpdateDialog让用户决定是否强制安装）
            // 校验失败≠文件损坏，可能是服务器配置错误或客户端hash过期。
            // 之前是直接delete()→用户失去已下载的包，无法绕过SHA。
            if (expectedSha256.isNotBlank()) {
                val actual = sha256OfFile(targetFile)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    return@withContext Result.Failure(
                        context.getString(R.string.update_sha_mismatch, expectedSha256, actual),
                        targetFile,
                    )
                }
            }

            // 最后一次进度（100%）
            onProgress(Progress(totalRead, totalBytes, 0))
            Result.Success(targetFile)
        } catch (t: Throwable) {
            DebugLog.e("Update", "download failed", t)
            targetFile.delete()
            val msg = t.message ?: t::class.java.simpleName ?: "unknown"
            // 非SHA错误（HTTP/网络）→没有本地包可强制安装，第二参传null
            Result.Failure(msg, null)
        }
    }

    companion object {
        /**
             * 启动下载（协程版）——调用方持有返回的Job用于取消。
             * 开始下载前会清掉缓存目录里的旧APK（避免每次下载都堆叠）。
             */
            fun start(
                context: Context,
                scope: CoroutineScope,
                url: String,
                targetFile: File,
                expectedSha256: String,
                onProgress: (Progress) -> Unit,
                onResult: (Result) -> Unit,
            ): Job = scope.launch(Dispatchers.IO) {
                targetFile.parentFile?.listFiles()?.forEach { it.delete() }
                val downloader = UpdateDownloader(targetFile)
                val result = downloader.download(context, url, expectedSha256, onProgress)
                withContext(Dispatchers.Main) { onResult(result) }
            }

        /** 文件SHA256（小写hex） */
        fun sha256OfFile(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
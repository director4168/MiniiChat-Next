package com.miniichatNext.carter.data.update

import android.content.Context
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.BuildConfig
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从固定URL拉取更新配置
 * 用JDK HttpURLConnection（不走Ktor客户端，避免再起一个engine）
 */
object UpdateRepository {

    /** 
    * 默认更新配置URL
    *
    * 自1.6.0版本起，更新检测支持识别设备架构，以支持下载32位包
    * v1.6.0版本起，服务端Json配置：
    * {
    *   "name": "MiniiChat-Next",       // 名称
    *   "time": "",                       // 时间
    *   "versionCode": "",               // 版本号（用于识别当前客户端是否为最新版本，如果服务端的该属性比客户端的该属性大，那么则判定客户端为旧版本）
    *   "version": "",                     // 版本号（旧版本使用，MiniiChat Next在早期版本判定客户端是否为新版本使用的是该属性，而不是现在的versionCode属性，如果您基于新版本修改，那么则不需要添加该属性）
    *   "versionName": "",               // 版本名
    *   "compulsory": false,             // 强制更新（false不强制，true强制）
    *   "update_content": "",            // 更新内容，支持markdown与部分BBCode
    *   "DownloadUrl": "",               // 64位版本下载链接
    *   "DownloadUrl_armabi_v7a": "",  // 32位版本下载链接
    *   "SHA256": "",                    // 64位版本安装包SHA256哈希
    *   "SHA256_armabi_v7a": ""        // 32位版本安装包SHA256哈希
    * }
    *
    * version属性是给旧版客户端提供版本解析的，实际使用过程中可以不添加
    */
    const val DEFAULT_URL =
        "https://director4168.github.io/AAAJsonConfiguration/MiniiChat-Next.json"

    /** 单次拉取配置的整体超时（含DNS/连接/读取），单位毫秒 */
    const val FETCH_TIMEOUT_MS = 15_000L

    private val json get() = AppJson.json

    sealed class Result {
        data class Success(val info: UpdateInfo) : Result()
        data class Failure(val message: String, val cause: Throwable? = null) : Result()
    }

    /**
     * 拉取更新配置。手动检查7秒超时（用户主动点=更长）（含DNS+连接+读取）。
     * 超时返回Failure("timeout")，不抛异常。
     */
    suspend fun checkForUpdate(context: Context, url: String = DEFAULT_URL): Result =
        withTimeoutOrNull(7_000L) {
            withIo { doFetch(context, url) }
        } ?: Result.Failure("timeout")

    private fun doFetch(context: Context, url: String): Result {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; MiniiChat-Next/${BuildConfig.VERSION_NAME}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Result.Failure("HTTP ${conn.responseCode}")
            } else {
                val body: String = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.isBlank()) {
                    Result.Failure(context.getString(R.string.update_empty_response))
                } else {
                    // 容错：剥离整行//注释（与app的assets配置同款约定）。
                    // 注意：旧版APP无此剥离逻辑，配置里加注释前须确认旧版本已淘汰。
                    val cleaned = body.lineSequence()
                        .filterNot { it.trimStart().startsWith("//") }
                        .joinToString("\n")
                    val info = json.decodeFromString(UpdateInfo.serializer(), cleaned)
                    Result.Success(info)
                }
            }
        } catch (t: Throwable) {
            DebugLog.e("Update", "check failed", t)
            val msg = t.message ?: t::class.java.simpleName ?: "unknown"
            Result.Failure(msg, t)
        } finally {
            conn.disconnect()
        }
    }

    private suspend inline fun <T> withIo(crossinline block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
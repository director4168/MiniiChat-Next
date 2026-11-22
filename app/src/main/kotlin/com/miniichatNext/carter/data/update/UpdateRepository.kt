/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import android.content.Context
import com.miniichatNext.carter.BuildConfig
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.boundedGetClient
import com.miniichatNext.carter.util.readTextBounded
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 从固定URL拉取更新配置
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
    * version属性仅供旧客户端使用，新客户端忽略该字段，只读取versionCode
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

    suspend fun checkForUpdate(context: Context, url: String = DEFAULT_URL): Result = try {
        val uri = URI(url)
        require(uri.scheme in setOf("https", "http") && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null) { "Invalid update URL" }
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            boundedGetClient.prepareGet(uri.toASCIIString()) {
                header(HttpHeaders.UserAgent, "MiniiChat-Next/${BuildConfig.VERSION_NAME}")
                header(HttpHeaders.Accept, "application/json")
                timeout {
                    requestTimeoutMillis = FETCH_TIMEOUT_MS
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 7000
                }
            }.execute { response ->
                if (response.status.value !in 200..299) return@execute Result.Failure("HTTP ${response.status.value}")
                val (body, _) = response.readTextBounded(256 * 1024)
                if (body.isBlank()) return@execute Result.Failure(context.getString(R.string.update_empty_response))
                val cleaned = body.removePrefix("\uFEFF").lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
                Result.Success(json.decodeFromString(UpdateInfo.serializer(), cleaned))
            }
        } ?: Result.Failure("timeout")
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (timeout: io.ktor.client.plugins.HttpRequestTimeoutException) {
        Result.Failure("timeout", timeout)
    } catch (failure: Exception) {
        DebugLog.w("Update", "check failed: ${failure.javaClass.simpleName}")
        Result.Failure(failure.message ?: "Update check failed", failure)
    }
}

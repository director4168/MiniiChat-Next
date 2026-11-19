/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import kotlinx.serialization.Serializable

/**
 * 远端更新配置（来自https://director4168.page.gd/update/MiniiChat-Next.json.txt）。
 *
 * 字段顺序与JSON一致；缺字段走默认值，向后兼容老版可以删字段。
 */
@Serializable
data class UpdateInfo(
    val name: String = "MiniiChat-Next",
    /** 发布时间，格式YYMMDD（如260922） */
    val time: String = "",
    /** 版本码（数字字符串，如"261013061"），新客户端唯一比较字段 */
    val versionCode: String = "",
    /** 展示名（如"v1.5.0-Beta"）——弹窗标题/正文显示 */
    val versionName: String = "",
    /** true=强制升级（不允许忽略）；false=可选升级 */
    val compulsory: Boolean = false,
    /** 更新说明，可包含markdown */
    val update_content: String = "",
    /** APK下载地址 */
    val DownloadUrl: String = "",
    /** APK SHA256哈希（小写hex，ARM64兼容旧配置） */
    val SHA256: String = "",
    /** ARM32独立下载地址 */
    val DownloadUrl_armabi_v7a: String = "",
    /** ARM32独立APK SHA256 */
    val SHA256_armabi_v7a: String = "",
)

/** 仅使用versionCode；未知的旧version字段由JSON解析器忽略。 */
fun UpdateInfo.remoteVersionCode(): Long = versionCode.trim().toLongOrNull() ?: 0L

/** 展示名缺省时展示versionCode，不读取旧字段。 */
fun UpdateInfo.displayVersion(): String = versionName.ifBlank { versionCode }

/** 忽略记录只以versionCode为键。 */
fun UpdateInfo.ignoreKey(): String = versionCode.trim()

/** 本地已安装versionCode */
fun localVersionCode(context: android.content.Context): Long = runCatching {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
    else @Suppress("DEPRECATION") pi.versionCode.toLong()
}.getOrDefault(0L)

/** 远端是否比本地新（按版本码数字比较） */
fun UpdateInfo.isRemoteNewer(context: android.content.Context): Boolean =
    remoteVersionCode() > localVersionCode(context)

data class UpdatePackage(val abi: String, val downloadUrl: String, val sha256: String)

fun UpdateInfo.packageForDevice(abis: List<String> = android.os.Build.SUPPORTED_ABIS.toList()): UpdatePackage {
    val abi = abis
        .firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
        ?: "arm64-v8a"
    return if (abi == "armeabi-v7a") {
        UpdatePackage(abi, DownloadUrl_armabi_v7a, SHA256_armabi_v7a)
    } else {
        UpdatePackage(abi, DownloadUrl, SHA256)
    }
}

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
    /** 版本码（数字字符串，如"261013061"）——比较用；legacy的version字段作兼容回退 */
    val versionCode: String = "",
    /** 展示名（如"v1.5.0-Beta"）——弹窗标题/正文显示 */
    val versionName: String = "",
    /** legacy：旧配置的比较字段，仅作回退兼容 */
    val version: String = "",
    /** true=强制升级（不允许忽略）；false=可选升级 */
    val compulsory: Boolean = false,
    /** 更新说明，可包含markdown */
    val update_content: String = "",
    /** APK下载地址 */
    val DownloadUrl: String = "",
    /** APK SHA256哈希（小写hex，ARM64兼容旧配置） */
    val SHA256: String = "",
    /** ARM32下载地址；为空时回退旧DownloadUrl */
    val DownloadUrl_armabi_v7a: String = "",
    /** ARM32 APK SHA256；为空时回退旧SHA256 */
    val SHA256_armabi_v7a: String = "",
)

/** 远端版本码（数字）；优先versionCode，legacy version兜底 */
fun UpdateInfo.remoteVersionCode(): Long =
    versionCode.toLongOrNull()
        ?: version.toLongOrNull()
        ?: 0L

/** 远端展示名：versionName优先，回退版本码（legacy version兜底） */
fun UpdateInfo.displayVersion(): String =
    versionName.ifBlank { versionCode.ifBlank { version } }

/** 忽略记录的键：版本码（legacy回退） */
fun UpdateInfo.ignoreKey(): String =
    versionCode.ifBlank { version }

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

fun UpdateInfo.packageForDevice(): UpdatePackage {
    val abi = android.os.Build.SUPPORTED_ABIS
        .firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
        ?: "arm64-v8a"
    return if (abi == "armeabi-v7a") {
        UpdatePackage(abi, DownloadUrl_armabi_v7a, SHA256_armabi_v7a)
    } else {
        UpdatePackage(abi, DownloadUrl, SHA256)
    }
}

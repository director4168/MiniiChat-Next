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
    /** 远端版本号（versionName，如"1.6.0"）——给解析器/比较用 */
    val version: String = "",
    /** 展示用版本号（如"1.6.0-rc.1"），弹窗标题/正文显示这个，version只做比较 */
    val versionCode: String = "",
    /** true=强制升级（不允许忽略）；false=可选升级 */
    val compulsory: Boolean = false,
    /** 更新说明，可包含markdown */
    val update_content: String = "",
    /** APK下载地址 */
    val DownloadUrl: String = "",
    /** APK SHA256哈希（小写hex） */
    val SHA256: String = "",
)

/**
 * 版本比较结果：>0表示a较新，<0表示a较旧，0表示相同。
 *
 * 支持semver简化版"1.2.3"、"1.2.3-rc.1"、"-beta"后缀。
 * 解析失败时回退到字符串字典序。
 */
fun compareVersions(a: String, b: String): Int {
    val aParts = a.split(".", "-").map { it.toIntOrNull() ?: 0 }
    val bParts = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
    val n = maxOf(aParts.size, bParts.size)
    for (i in 0 until n) {
        val av = aParts.getOrElse(i) { 0 }
        val bv = bParts.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}
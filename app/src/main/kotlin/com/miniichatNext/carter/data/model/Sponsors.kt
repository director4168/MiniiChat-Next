package com.miniichatNext.carter.data.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Sponsor(
    val name: String,
    /** 金额（自由格式，例如"¥100"、"50 USD"）；缺省不显示 */
    val amount: String? = null,
    /** 赞助日期（自由格式，例如"2025-09"） */
    val date: String? = null,
    /** 寄语（可选） */
    val message: String? = null,
    /** 可选链接（个人主页/仓库等） */
    val url: String? = null,
)

@Serializable
data class SponsorsFile(
    val sponsors: List<Sponsor> = emptyList(),
)

object Sponsors {
    /**
     * 读取assets/data/sponsors.json中的赞助者列表。
     * 解析失败时返回空列表（绝不让App崩——赞助名单是展示性内容）。
     */
    fun loadFromAssets(context: Context): List<Sponsor> =
        com.miniichatNext.carter.util.AssetJsonLoader
            .load(context, "data/sponsors.json", SponsorsFile.serializer(), ::stripJsonLineComments)
            ?.sponsors
            ?: emptyList()
}

/**
 * 过滤整行的//注释：json标准不允许注释，但这两个配置文件约定允许写说明行。
 * 只处理整行注释（行内注释不支持，避免与URL里的//冲突）。
 */
internal fun stripJsonLineComments(text: String): String =
    text.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
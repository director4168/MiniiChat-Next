/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProjectCredit(
    val name: String,
    /** 简介（直接文案，随json配置） */
    val desc: String = "",
    /** 仓库链接（缺省时该行不可点击） */
    val url: String = "",
)

@Serializable
data class ProjectsFile(
    val projects: List<ProjectCredit> = emptyList(),
)

object ProjectCredits {
    fun loadFromAssets(context: Context): List<ProjectCredit> =
        com.miniichatNext.carter.util.AssetJsonLoader
            .load(context, "data/projects.json", ProjectsFile.serializer(), ::stripJsonLineComments)
            ?.projects
            ?: emptyList()
}

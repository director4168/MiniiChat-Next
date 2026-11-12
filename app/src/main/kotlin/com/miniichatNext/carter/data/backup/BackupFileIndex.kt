/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupFileIndex(val path: String, val size: Long, val md5: String)

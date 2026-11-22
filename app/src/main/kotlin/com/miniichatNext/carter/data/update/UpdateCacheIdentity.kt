/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import kotlinx.serialization.Serializable

@Serializable
internal data class CachedUpdate(
    val info: UpdateInfo,
    val abi: String,
    val url: String,
    val expectedSha256: String,
    val actualSha256: String,
    val size: Long,
    val downloadLocation: String? = null,
)

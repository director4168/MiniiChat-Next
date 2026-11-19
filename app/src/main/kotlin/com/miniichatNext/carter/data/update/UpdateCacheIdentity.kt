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


internal object UpdateCacheIdentity {
    fun matches(cached: CachedUpdate, info: UpdateInfo, selected: UpdatePackage): Boolean =
        cached.info.remoteVersionCode() == info.remoteVersionCode() && cached.info.versionName == info.versionName &&
            cached.abi == selected.abi && cached.url == selected.downloadUrl &&
            cached.expectedSha256.equals(selected.sha256.trim(), true)
}

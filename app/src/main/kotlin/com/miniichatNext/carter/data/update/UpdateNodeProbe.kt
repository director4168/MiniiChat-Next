/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

internal enum class UpdateProbeStatus { WAITING, TESTING, SUCCESS, FAILED }

internal data class UpdateNodeProbe(
    val key: String,
    val status: UpdateProbeStatus = UpdateProbeStatus.WAITING,
    val latencyMs: Long? = null,
    val speedBytesPerSec: Long = 0,
    val error: String? = null,
)

internal fun List<UpdateNodeProbe>.withProbeStarted(key: String): List<UpdateNodeProbe> = map { node ->
    if (node.key == key) node.copy(status = UpdateProbeStatus.TESTING) else node
}

internal fun List<UpdateNodeProbe>.withProbeResult(result: UpdateSource): List<UpdateNodeProbe> = map { node ->
    if (node.key == result.key) node.copy(
        status = if (result.error == null) UpdateProbeStatus.SUCCESS else UpdateProbeStatus.FAILED,
        latencyMs = result.latencyMs,
        speedBytesPerSec = result.speedBytesPerSec,
        error = result.error,
    ) else node
}

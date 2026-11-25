/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

internal enum class LocalModelImportPhase { IDLE, COPYING, DOWNLOADING, VERIFYING, DONE, FAILED, CANCELLED }

internal data class LocalModelImportState(
    val phase: LocalModelImportPhase = LocalModelImportPhase.IDLE,
    val bytes: Long = 0,
    val total: Long = -1,
    val bytesPerSec: Long = 0,
    val fileName: String? = null,
    val importedName: String? = null,
    val error: String? = null,
) {
    val transferring: Boolean get() = phase == LocalModelImportPhase.COPYING || phase == LocalModelImportPhase.DOWNLOADING
    val active: Boolean get() = transferring || phase == LocalModelImportPhase.VERIFYING
    val fraction: Float? get() = total.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

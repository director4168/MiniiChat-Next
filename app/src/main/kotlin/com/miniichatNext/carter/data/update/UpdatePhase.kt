/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

internal enum class UpdateDialogStage { PREPARING, PROBING, DOWNLOADING, INSTALLATION }

internal enum class UpdatePhase {
    CHECKING_CACHE, PROBING, DOWNLOADING, VERIFYING, READY, FAILED, PROBE_FAILED, VERIFICATION_FAILED, PUBLICATION_FAILED, CANCELLED;

    val active: Boolean get() = this in setOf(CHECKING_CACHE, PROBING, DOWNLOADING, VERIFYING)

    val dialogStage: UpdateDialogStage get() = when (this) {
        CHECKING_CACHE -> UpdateDialogStage.PREPARING
        PROBING, PROBE_FAILED -> UpdateDialogStage.PROBING
        DOWNLOADING, FAILED, CANCELLED -> UpdateDialogStage.DOWNLOADING
        VERIFYING, READY, VERIFICATION_FAILED, PUBLICATION_FAILED -> UpdateDialogStage.INSTALLATION
    }

    val showsProbes: Boolean get() = dialogStage == UpdateDialogStage.PROBING

    fun failed(): UpdatePhase = when (this) {
        PROBING, PROBE_FAILED -> PROBE_FAILED
        VERIFYING, READY, VERIFICATION_FAILED, PUBLICATION_FAILED -> VERIFICATION_FAILED
        else -> FAILED
    }
}

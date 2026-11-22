/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateDownloadSnapshot
import com.miniichatNext.carter.data.update.UpdatePhase
import com.miniichatNext.carter.data.update.displayVersion
import java.util.Locale

@Composable
internal fun UpdateDownloadDialog(
    state: UpdateDownloadSnapshot,
    onRetry: () -> Unit,
    onForceInstall: () -> Unit,
    onInstallNow: () -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
) {
    var confirmForce by remember(state.file, state.forceDigest) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onHide,
        title = { Text(stringResource(
            when (state.phase) {
                UpdatePhase.PROBING -> R.string.update_probe_title
                UpdatePhase.PROBE_FAILED -> R.string.update_probe_failed_title
                UpdatePhase.VERIFYING -> R.string.update_check_install_title
                UpdatePhase.VERIFICATION_FAILED -> R.string.update_verification_failed_title
                UpdatePhase.PUBLICATION_FAILED -> R.string.update_cache_publish_failed
                UpdatePhase.CANCELLED -> R.string.update_cancelled
                UpdatePhase.READY -> R.string.update_download_done_title
                UpdatePhase.FAILED -> R.string.update_download_failed_title
                UpdatePhase.CHECKING_CACHE -> R.string.update_checking_cache
                UpdatePhase.DOWNLOADING -> R.string.update_downloading_title
            }, state.info.displayVersion())) },
        text = {
            // 同一Dialog中原位切换阶段，不先关闭窗口，避免遮罩和底层页面闪动。
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp)) {
                val scroll = remember(state.phase.dialogStage) { androidx.compose.foundation.ScrollState(0) }
                Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (state.phase) {
                        UpdatePhase.CHECKING_CACHE -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        UpdatePhase.PROBING, UpdatePhase.PROBE_FAILED -> ProbeStage(state)
                        UpdatePhase.DOWNLOADING -> DownloadStage(state)
                        UpdatePhase.VERIFYING, UpdatePhase.READY, UpdatePhase.VERIFICATION_FAILED, UpdatePhase.PUBLICATION_FAILED -> InstallationStage(state)
                        UpdatePhase.CANCELLED -> Text(stringResource(R.string.update_cancelled))
                        UpdatePhase.FAILED -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.READY -> Button(onClick = onInstallNow) { Text(stringResource(R.string.update_install_now)) }
                UpdatePhase.VERIFICATION_FAILED -> Column(horizontalAlignment = Alignment.End) {
                    if (state.file != null && state.forceDigest != null) TextButton(onClick = { confirmForce = true }) {
                        Text(stringResource(R.string.update_force_install))
                    }
                    Button(onClick = onRetry) { Text(stringResource(R.string.update_retry_download)) }
                }
                UpdatePhase.FAILED, UpdatePhase.PROBE_FAILED, UpdatePhase.PUBLICATION_FAILED, UpdatePhase.CANCELLED -> Button(onClick = onRetry) { Text(stringResource(R.string.update_retry_download)) }
                else -> TextButton(onClick = onHide) { Text(stringResource(R.string.update_hide_download)) }
            }
        },
        dismissButton = {
            if (state.active) TextButton(onClick = onCancel) { Text(stringResource(R.string.update_cancel_download)) }
            else TextButton(onClick = onHide) { Text(stringResource(R.string.update_close)) }
        },
    )
    if (confirmForce) AlertDialog(
        onDismissRequest = { confirmForce = false },
        title = { Text(stringResource(R.string.update_force_install)) },
        text = { Text(stringResource(R.string.update_force_install_confirm)) },
        confirmButton = { TextButton(onClick = { confirmForce = false; onForceInstall() }) { Text(stringResource(R.string.update_force_install)) } },
        dismissButton = { TextButton(onClick = { confirmForce = false }) { Text(stringResource(R.string.cancel)) } },
    )

}

@Composable
private fun ProbeStage(state: UpdateDownloadSnapshot) {
    if (state.phase == UpdatePhase.PROBING) {
        LinearProgressIndicator(progress = { if (state.sourceCount > 0) state.tested.toFloat() / state.sourceCount else 0f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.update_probing_nodes, state.tested, state.sourceCount))
    } else Text(state.message, color = MaterialTheme.colorScheme.error)
    if (state.phase.showsProbes && state.probes.isNotEmpty()) UpdateProbeList(state.probes, "")
}

@Composable
private fun DownloadStage(state: UpdateDownloadSnapshot) {
    Text(if (state.direct) stringResource(R.string.update_direct_download)
        else stringResource(R.string.update_using_node, state.source))
    val p = state.progress
    if (p.totalBytes > 0) LinearProgressIndicator(progress = { (p.bytesRead.toFloat() / p.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(formatBytes(p.bytesRead) + if (p.totalBytes > 0) " / ${formatBytes(p.totalBytes)}" else "",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    Text(formatBytes(p.bytesPerSec) + "/s", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InstallationStage(state: UpdateDownloadSnapshot) {
    Text(state.info.displayVersion(), style = MaterialTheme.typography.bodyMedium)
    if (state.reused) Text(stringResource(R.string.update_reused_package))
    else Text(stringResource(R.string.update_transfer_complete))
    when (state.phase) {
        UpdatePhase.VERIFYING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_verifying_package))
        }
        UpdatePhase.READY -> {
            Text(stringResource(R.string.update_verified_package), style = MaterialTheme.typography.bodySmall)
            if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        UpdatePhase.VERIFICATION_FAILED, UpdatePhase.PUBLICATION_FAILED -> Text(state.message, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}

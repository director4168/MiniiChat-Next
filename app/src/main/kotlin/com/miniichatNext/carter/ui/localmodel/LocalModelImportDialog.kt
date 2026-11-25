/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.localmodel

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.localmodel.LocalModelImportPhase
import com.miniichatNext.carter.data.localmodel.LocalModelImportState

@Composable
internal fun LocalModelImportDialog(state: LocalModelImportState, onCancel: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val title = when (state.phase) {
        LocalModelImportPhase.IDLE -> return
        LocalModelImportPhase.COPYING -> R.string.local_model_copying
        LocalModelImportPhase.DOWNLOADING -> R.string.local_model_downloading
        LocalModelImportPhase.VERIFYING -> R.string.local_model_verifying
        LocalModelImportPhase.DONE -> R.string.local_model_import_done_title
        LocalModelImportPhase.FAILED -> R.string.local_model_import_failed_title
        LocalModelImportPhase.CANCELLED -> R.string.update_cancelled
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.fileName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when (state.phase) {
                    LocalModelImportPhase.COPYING, LocalModelImportPhase.DOWNLOADING -> {
                        val fraction = state.fraction
                        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.local_model_transfer_size,
                            Formatter.formatFileSize(context, state.bytes),
                            if (state.total > 0) Formatter.formatFileSize(context, state.total)
                            else stringResource(R.string.local_model_unknown_size)),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        Text(stringResource(R.string.local_model_transfer_speed,
                            Formatter.formatFileSize(context, state.bytesPerSec)), style = MaterialTheme.typography.bodySmall)
                    }
                    LocalModelImportPhase.VERIFYING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.local_model_verifying_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    LocalModelImportPhase.DONE -> Text(stringResource(R.string.local_model_imported,
                        state.importedName.orEmpty()))
                    LocalModelImportPhase.FAILED -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    LocalModelImportPhase.IDLE, LocalModelImportPhase.CANCELLED -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(if (state.active) R.string.update_hide_download else R.string.update_close))
            }
        },
        dismissButton = {
            if (state.active) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.debug.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugDataWipe

@Composable
internal fun DebugClearDataDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val keyword = stringResource(R.string.debug_clear_data_keyword)
    var finalStep by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!requested) onDismiss() },
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(if (finalStep) R.string.debug_clear_data_final_title else R.string.debug_clear_data_confirm_title)) },
        text = {
            if (finalStep) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.debug_clear_data_final_message, keyword))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; failed = false },
                        singleLine = true,
                        enabled = !requested,
                        placeholder = { Text(keyword) },
                    )
                    if (failed) Text(stringResource(R.string.debug_clear_data_failed), color = MaterialTheme.colorScheme.error)
                }
            } else Text(stringResource(R.string.debug_clear_data_confirm_message))
        },
        confirmButton = {
            TextButton(
                enabled = !requested && (!finalStep || input.trim() == keyword),
                onClick = {
                    if (!finalStep) finalStep = true
                    else {
                        requested = DebugDataWipe.clear(context.applicationContext)
                        failed = !requested
                    }
                },
            ) {
                Text(stringResource(if (finalStep) R.string.debug_clear_data_action else R.string.debug_clear_data_continue),
                    color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(enabled = !requested, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

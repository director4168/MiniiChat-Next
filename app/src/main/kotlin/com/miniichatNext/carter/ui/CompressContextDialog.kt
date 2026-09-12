package com.miniichatNext.carter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    onConfirm: (targetTokens: Int, keepRecentMessages: Int, additionalPrompt: String) -> Unit
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var targetTokens by remember { mutableIntStateOf(2000) }
    var keepRecentMessages by remember { mutableIntStateOf(32) }
    val tokenOptions = listOf(500, 1000, 2000, 4000)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.context_compress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    stringResource(R.string.compress_target_tokens),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tokenOptions.forEach { tokens ->
                        val selected = tokens == targetTokens
                        TextButton(onClick = { targetTokens = tokens }) {
                            Text(
                                "$tokens",
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = keepRecentMessages.toString(),
                    onValueChange = { v ->
                        keepRecentMessages = v.toIntOrNull() ?: 0
                    },
                    label = { Text(stringResource(R.string.compress_keep_recent)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = additionalPrompt,
                    onValueChange = { additionalPrompt = it },
                    label = { Text(stringResource(R.string.compress_additional_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(targetTokens, keepRecentMessages, additionalPrompt)
            }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

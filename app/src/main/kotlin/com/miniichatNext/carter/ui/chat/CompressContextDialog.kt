package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import com.miniichatNext.carter.R

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    isCompressing: Boolean,
    onConfirm: (targetTokens: Int, keepRecentMessages: Int, additionalPrompt: String) -> Job?
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var targetTokens by remember { mutableIntStateOf(2000) }
    var keepRecentMessages by remember { mutableIntStateOf(32) }
    var compressJob by remember { mutableStateOf<Job?>(null) }
    val tokenOptions = listOf(500, 1000, 2000, 4000)
    LaunchedEffect(compressJob) {
        val job = compressJob ?: return@LaunchedEffect
        job.join()
        if (compressJob === job) {
            compressJob = null
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isCompressing) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = !isCompressing,
            dismissOnClickOutside = !isCompressing,
        ),

        title = { Text(stringResource(R.string.context_compress)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isCompressing) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(42.dp), strokeWidth = 4.dp)
                    Text(stringResource(R.string.compressing_context))
                    Spacer(Modifier.size(8.dp))
                } else {
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
            }
        },
        confirmButton = {
            if (!isCompressing) {
                TextButton(onClick = {
                    compressJob = onConfirm(targetTokens, keepRecentMessages, additionalPrompt)
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isCompressing) TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.migration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.migration.ConversationMigration

@Composable
internal fun ConversationMigrationScreen(
    state: ConversationMigration.Status,
    retry: () -> Unit,
    continueToApp: () -> Unit,
) {
    var showReport by remember { mutableStateOf(false) }
    BackHandler { }
    if (showReport) {
        val app = LocalContext.current.applicationContext
        var lines by remember { mutableStateOf<List<String>?>(null) }
        LaunchedEffect(app) { lines = ConversationMigration.report(app) }
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text(stringResource(R.string.migration_report)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.migration_report_legend))
                    lines.orEmpty().forEach { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { showReport = false }) { Text(stringResource(R.string.migration_report_close)) } },
        )
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.migration_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(state.phase.label), style = MaterialTheme.typography.titleMedium)
                when (state.phase) {
                    ConversationMigration.Phase.FAILED -> {
                        Text(stringResource(state.detail ?: R.string.migration_invalid))
                        if (state.detail != R.string.migration_cleanup_failed) {
                            Text(stringResource(R.string.migration_retained))
                        }
                        Button(onClick = retry) { Text(stringResource(R.string.migration_retry)) }
                        TextButton(onClick = { showReport = true }) { Text(stringResource(R.string.migration_report)) }
                    }
                    ConversationMigration.Phase.WARNINGS -> {
                        Text(stringResource(R.string.migration_summary, state.conversations, state.messages))
                        Text(stringResource(R.string.migration_issues, state.warnings, state.skipped))
                        Text(stringResource(if (state.legacyRemoved) R.string.migration_legacy_removed else R.string.migration_retained))
                        Button(onClick = continueToApp) { Text(stringResource(R.string.migration_continue)) }
                        TextButton(onClick = { showReport = true }) { Text(stringResource(R.string.migration_report)) }
                    }
                    else -> {
                        if (state.phase == ConversationMigration.Phase.WRITING && state.total > 0) {
                            LinearProgressIndicator(progress = { state.processed.toFloat() / (state.total + 1) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.migration_notification_progress, state.processed, state.total))
                            Text(stringResource(R.string.migration_summary, state.conversations, state.messages))
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.migration_wait_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

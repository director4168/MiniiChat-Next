/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateDownloadSources
import com.miniichatNext.carter.data.update.UpdateMirrorNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun UpdateNodesEditor(
    initial: List<UpdateMirrorNode>,
    onDismiss: () -> Unit,
    onSave: suspend (List<UpdateMirrorNode>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf(initial.toList()) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.update_nodes_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    TextButton(enabled = !saving && nodes.size < 64, onClick = {
                        name = ""; url = ""; error = null; editing = -1
                    }) { Text(stringResource(R.string.update_node_add)) }
                    TextButton(enabled = !saving, onClick = { resetConfirm = true }) { Text(stringResource(R.string.debug_theme_reset_defaults)) }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(nodes) { index, node ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(node.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Switch(enabled = !saving, checked = node.enabled, onCheckedChange = { enabled ->
                                    nodes = nodes.toMutableList().apply { set(index, node.copy(enabled = enabled)) }
                                })
                                IconButton(enabled = !saving, onClick = { name = node.name; url = node.url; error = null; editing = index }) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.edit))
                                }
                                IconButton(enabled = !saving, onClick = { nodes = nodes.toMutableList().apply { removeAt(index) } }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                                }
                            }
                            Text(node.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (nodes.isEmpty()) Text(stringResource(R.string.update_nodes_empty))
                if (editing == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        UpdateDownloadSources.validateNodes(nodes)
                        onSave(nodes)
                        onDismiss()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = context.getString(R.string.update_nodes_save_failed, failure.message.orEmpty()) }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    editing?.let { index ->
        AlertDialog(
            onDismissRequest = { editing = null; error = null },
            title = { Text(stringResource(if (index < 0) R.string.update_node_add else R.string.update_node_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it; error = null }, label = { Text(stringResource(R.string.update_node_url)) }, maxLines = 4)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                    val node = UpdateMirrorNode(name.trim(), url.trim(), if (index < 0) true else nodes[index].enabled)
                    val updated = nodes.toMutableList().apply { if (index < 0) add(node) else set(index, node) }
                    try {
                        UpdateDownloadSources.validateNodes(updated)
                        nodes = updated; editing = null; error = null
                    } catch (_: Exception) { error = context.getString(R.string.update_nodes_invalid) }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null; error = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (resetConfirm) AlertDialog(
        onDismissRequest = { resetConfirm = false },
        title = { Text(stringResource(R.string.debug_theme_reset_defaults)) },
        text = { Text(stringResource(R.string.update_nodes_reset_message)) },
        confirmButton = { TextButton(onClick = { nodes = UpdateDownloadSources.defaultNodes(); resetConfirm = false; error = null }) { Text(stringResource(R.string.debug_theme_reset_defaults)) } },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

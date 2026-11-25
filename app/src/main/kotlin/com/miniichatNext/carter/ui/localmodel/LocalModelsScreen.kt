/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.localmodel

import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.BuildConfig
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.localmodel.LocalModelImportService
import com.miniichatNext.carter.data.localmodel.LocalModelRecord
import com.miniichatNext.carter.data.localmodel.LocalModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelsScreen(onStartImport: (Uri?, String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by LocalModelStore.records.collectAsState()
    val status by LocalModelImportService.state.collectAsState()
    val supported = LocalModelStore.supported
    var showUrl by rememberSaveable { mutableStateOf(false) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    val startImport: (Uri?, String?) -> Unit = { uri, address ->
        error = null
        onStartImport(uri, address)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (name?.endsWith(".gguf", ignoreCase = true) != true &&
            mime !in setOf("application/x-gguf", "application/gguf")) {
            Toast.makeText(context, R.string.local_model_pick_gguf, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!status.active) startImport(uri, null)
    }
    val openGgufPicker = {
        showSheet = false
        Toast.makeText(context, R.string.local_model_pick_gguf, Toast.LENGTH_SHORT).show()
        filePicker.launch(arrayOf("application/x-gguf", "application/gguf", "application/octet-stream"))
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.local_model_title)) }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            })
        }, floatingActionButton = {
            if (supported) FloatingActionButton(onClick = {
                if (status.active) LocalModelImportService.show() else showSheet = true
            },
                content = { Icon(Icons.Default.Add, contentDescription = stringResource(
                    if (status.active) R.string.local_model_show_progress else R.string.local_model_add)) })
        }) { insets ->
            if (!supported) {
                LocalModelDisabledContent(Modifier.fillMaxSize().padding(insets))
                return@Scaffold
            }
            LazyColumn(Modifier.padding(insets).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(stringResource(R.string.local_model_description))
                }
                if (status.active) item {
                    TextButton(onClick = LocalModelImportService::show) {
                        Text(stringResource(R.string.local_model_show_progress))
                    }
                }
                error?.let { reason ->
                    item { Text(reason, color = MaterialTheme.colorScheme.error) }
                }
                if (models.isEmpty() && !status.active) item { Text(stringResource(R.string.local_model_empty)) }
                items(models, key = LocalModelRecord::id) { model ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            Text(Formatter.formatFileSize(context, model.size), style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { error = null; editingId = model.id }, enabled = !updating) {
                                    Text(stringResource(R.string.local_model_parameters))
                                }
                                TextButton(onClick = { error = null; deletingId = model.id }, enabled = !updating) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!supported) LocalModelDisabledRibbon(Modifier.align(Alignment.TopEnd))
    }

    if (supported && showSheet && !status.active) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.local_model_add), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                LocalModelSheetRow(icon = Icons.Default.FileDownload,
                    label = stringResource(R.string.local_model_import_file), onClick = openGgufPicker)
                LocalModelSheetRow(icon = Icons.Default.Link,
                    label = stringResource(R.string.local_model_from_url), onClick = {
                        showSheet = false
                        showUrl = true
                    })
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (supported && showUrl) AlertDialog(
        onDismissRequest = { showUrl = false },
        title = { Text(stringResource(R.string.local_model_from_url)) },
        text = { OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text(stringResource(R.string.local_model_url)) }) },
        confirmButton = { TextButton(enabled = url.isNotBlank() && !status.active, onClick = {
            startImport(null, url.trim())
            url = ""
            showUrl = false
        }) { Text(stringResource(R.string.local_model_start_import)) } },
        dismissButton = { TextButton(onClick = { showUrl = false }) { Text(stringResource(R.string.cancel)) } },
    )

    models.firstOrNull { it.id == editingId }?.let { model ->
        LocalModelParametersDialog(model, updating, onDismiss = { editingId = null }) { name, parameters ->
            scope.launch {
                updating = true
                try {
                    LocalModelStore.update(context, model.id, name, parameters)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.localizedMessage ?: context.getString(R.string.local_model_save_failed)
                } finally {
                    updating = false
                    editingId = null
                }
            }
        }
    }
    models.firstOrNull { it.id == deletingId }?.let { model ->
        AlertDialog(onDismissRequest = { deletingId = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.local_model_delete_title)) },
            text = { Text(stringResource(R.string.local_model_delete_message, model.name)) },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                deletingId = null
                scope.launch {
                    updating = true
                    try {
                        LocalModelStore.delete(context, model.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.localizedMessage ?: context.getString(R.string.local_model_save_failed)
                    } finally { updating = false }
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } })
    }
}

@Composable
private fun LocalModelDisabledContent(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.workspace_disabled_title), style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.local_model_disabled_reason), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.workspace_disabled_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
                ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LocalModelDisabledRibbon(modifier: Modifier = Modifier) {
    val ribbonColor = MaterialTheme.colorScheme.error
    Box(modifier.size(140.dp), contentAlignment = Alignment.TopEnd) {
        Canvas(Modifier.fillMaxSize()) {
            val edge = size.width
            drawPath(Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width - edge, 0f)
                lineTo(size.width, edge)
                close()
            }, color = ribbonColor)
        }
        Text(stringResource(R.string.workspace_disabled_ribbon),
            modifier = Modifier.padding(top = 54.dp, end = 8.dp).rotate(45f),
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LocalModelSheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

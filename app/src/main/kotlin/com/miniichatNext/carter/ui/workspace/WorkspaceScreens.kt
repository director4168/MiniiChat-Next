package com.miniichatNext.carter.ui.workspace

import android.content.Context

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.miniichatNext.carter.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.data.workspace.RootfsInstallProgress
import com.miniichatNext.carter.data.workspace.RootfsInstallStage
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.data.workspace.WorkspaceFileEntry
import com.miniichatNext.carter.data.workspace.WorkspaceRepository
import com.miniichatNext.carter.data.workspace.WorkspaceShellStatus
import com.miniichatNext.carter.data.workspace.WorkspaceStorageArea
import com.miniichatNext.carter.data.workspace.WorkspaceToolDefaultApprovals
import com.miniichatNext.carter.ui.components.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

@Composable
fun WorkspaceListScreen(
    repository: WorkspaceRepository,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaces by repository.workspacesFlow.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = stringResource(R.string.workspace_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = { draft = ""; creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.workspace_new))
                }
            }
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.workspace_multi_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (workspaces.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(stringResource(R.string.workspace_empty_title), style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.workspace_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(onClick = { draft = ""; creating = true }) { Text(stringResource(R.string.workspace_new)) }
                        }
                    }
                }
            } else {
                items(workspaces, key = { it.id }) { ws ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(ws.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ws.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "root: ${ws.root} · ${statusLabel(context, ws.shellStatus)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { draft = ws.name; renaming = ws }) { Text(stringResource(R.string.rename)) }
                            IconButton(onClick = { pendingDelete = ws }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.workspace_new)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repository.create(draft) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        creating = false
                    }
                }) { Text(stringResource(R.string.common_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    renaming?.let { ws ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.workspace_rename)) },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repository.rename(ws.id, draft) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        renaming = null
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    pendingDelete?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.workspace_delete_confirm_title)) },
            text = { Text(stringResource(R.string.workspace_delete_confirm_message, ws.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        repository.delete(ws.id)
                        Toast.makeText(context, context.getString(R.string.workspace_deleted), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun statusLabel(context: Context, status: String): String = when (status) {
    WorkspaceShellStatus.READY.name -> context.getString(R.string.workspace_status_ready)
    WorkspaceShellStatus.INSTALLING.name -> context.getString(R.string.workspace_status_installing)
    WorkspaceShellStatus.BROKEN.name -> context.getString(R.string.workspace_status_broken)
    else -> context.getString(R.string.workspace_status_missing)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDetailScreen(
    repository: WorkspaceRepository,
    workspaceId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaces by repository.workspacesFlow.collectAsState(initial = emptyList())
    val workspace = workspaces.firstOrNull { it.id == workspaceId }

    var rootfsUrl by remember { mutableStateOf(DEFAULT_ROOTFS_URL) }
    var progress by remember { mutableStateOf<RootfsInstallProgress?>(null) }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var currentPath by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var terminalOut by remember { mutableStateOf("") }

    fun refreshFiles(path: String = currentPath) {
        scope.launch {
            currentPath = path
            files = runCatching {
                withContext(Dispatchers.IO) {
                    repository.listFiles(workspaceId, WorkspaceStorageArea.FILES, path)
                }
            }.getOrDefault(emptyList())
        }
    }

    androidx.compose.runtime.LaunchedEffect(workspaceId) { refreshFiles("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = workspace?.name ?: stringResource(R.string.workspace_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = { refreshFiles() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.workspace_env_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        statusLabel(context, workspace?.shellStatus ?: WorkspaceShellStatus.DISABLED.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rootfsUrl,
                        onValueChange = { rootfsUrl = it },
                        label = { Text(stringResource(R.string.workspace_rootfs_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && rootfsUrl.isNotBlank(),
                            onClick = {
                                busy = true
                                progress = null
                                scope.launch {
                                    runCatching {
                                        repository.installRootfs(workspaceId, rootfsUrl.trim()) { p -> progress = p }
                                    }.onSuccess {
                                        Toast.makeText(context, context.getString(R.string.workspace_install_done), Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, context.getString(R.string.workspace_install_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                    busy = false
                                }
                            }
                        ) { Text(if (busy) stringResource(R.string.workspace_installing) else stringResource(R.string.workspace_install)) }
                    }
                    progress?.let { p ->
                        Spacer(modifier = Modifier.height(8.dp))
                        when (p.stage) {
                            RootfsInstallStage.DOWNLOADING -> {
                                val total = p.totalBytes
                                Text(
                                    if (total != null && total > 0)
                                        stringResource(R.string.workspace_downloading_total, p.bytesRead / 1024 / 1024, total / 1024 / 1024)
                                    else stringResource(R.string.workspace_downloading, p.bytesRead / 1024 / 1024),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (total != null && total > 0) {
                                    LinearProgressIndicator(
                                        progress = { (p.bytesRead.toFloat() / total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            RootfsInstallStage.EXTRACTING -> Text(
                                stringResource(R.string.workspace_extracting, p.entriesExtracted, p.currentEntry.orEmpty().take(50)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            RootfsInstallStage.INSTALLED -> Text(
                                stringResource(R.string.workspace_env_installed),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.workspace_files_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (currentPath.isNotBlank()) {
                TextButton(onClick = { refreshFiles(currentPath.substringBeforeLast('/', "")) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.workspace_parent_dir, currentPath))
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (files.isEmpty()) {
                        Text(
                            stringResource(R.string.common_dir_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        files.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = entry.isDirectory) {
                                        refreshFiles(entry.path)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Default.Folder
                                    else Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (entry.isDirectory) "" else "${entry.sizeBytes} B",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.workspace_terminal_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = command,
                                onValueChange = { command = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (command.isEmpty()) {
                                        Text(
                                            "ls -la; pwd",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            )
                        }
                        TextButton(
                            enabled = command.isNotBlank() && !busy,
                            onClick = {
                                val cmd = command
                                command = ""
                                scope.launch {
                                    val out = withContext(Dispatchers.IO) {
                                        runCatching {
                                            repository.executeCommand(workspaceId, cmd, currentPath, 600_000L)
                                        }.fold(
                                            onSuccess = { r ->
                                                buildString {
                                                    append("$ ").append(cmd).append('\n')
                                                    append("exitCode=").append(r.exitCode)
                                                    if (r.timedOut) append(context.getString(R.string.common_timed_out))
                                                    append('\n')
                                                    if (r.stdout.isNotEmpty()) append(r.stdout)
                                                    if (r.stderr.isNotEmpty()) append("\n[stderr]\n").append(r.stderr)
                                                }
                                            },
                                            onFailure = { context.getString(R.string.common_exec_error, it.message ?: "") }
                                        )
                                    }
                                    terminalOut = out
                                }
                            }
                        ) { Text(stringResource(R.string.workspace_run)) }
                    }
                    if (terminalOut.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            terminalOut,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.workspace_approval_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val overrides = workspace?.toolApprovalOverrides().orEmpty()
                    WorkspaceToolDefaultApprovals.forEach { (toolName, default) ->
                        val current = overrides[toolName] ?: default
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    toolName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    stringResource(R.string.mcp_tool_approval),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = current,
                                onCheckedChange = { value ->
                                    scope.launch { repository.setToolApproval(workspaceId, toolName, value) }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

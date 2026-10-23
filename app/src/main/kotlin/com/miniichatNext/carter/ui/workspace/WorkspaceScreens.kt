package com.miniichatNext.carter.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.workspace.RootfsInstallManager
import com.miniichatNext.carter.data.workspace.RootfsInstallStage
import com.miniichatNext.carter.data.workspace.RootfsInstallState
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.data.workspace.WorkspaceRepository
import com.miniichatNext.carter.data.workspace.WorkspaceShellStatus
import com.miniichatNext.carter.data.workspace.WorkspaceStorageArea
import com.miniichatNext.carter.data.workspace.WorkspaceCategoryDefaultApprovals
import com.miniichatNext.carter.data.workspace.WorkspaceToolCategory
import com.miniichatNext.carter.data.workspace.storageKey
import com.miniichatNext.carter.ui.components.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.content.Intent
import java.io.File
import com.miniichatNext.carter.ui.markdown.MarkdownText

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceListScreen(
    repository: WorkspaceRepository,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onWorkspaceClick: (String) -> Unit,
    onCreateNamed: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val workspaces by repository.workspacesFlow.collectAsState(initial = emptyList())

    // 重命名/删除对话框状态（函数内remember，绝不能用顶层var——跨实例共享且不触发重组）
    var renaming by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkspaceEntity?>(null) }
    // 创建工作区：先命名（非空合法）再进创建流程
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // Scaffold不给topBar加状态栏insets，手动补（其它页面在Column层已处理）
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                SettingsTopBar(stringResource(R.string.workspace_title), onBack)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.workspace_new))
            }
        }
    ) { padding ->
        if (workspaces.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workspace_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(workspaces, key = { it.id }) { ws ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onWorkspaceClick(ws.id) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ws.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    statusLabel(LocalContext.current, ws.shellStatus),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { renaming = ws }) {
                                Text(stringResource(R.string.rename))
                            }
                            IconButton(onClick = { pendingDelete = ws }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }    // 重命名对话框（用InputDialog通用组件）
    renaming?.let { target ->
        com.miniichatNext.carter.ui.components.InputDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.common_name),
            initial = target.name,
            onConfirm = { name ->
                val id = target.id
                renaming = null
                scope.launch {
                    val ok = runCatching { repository.rename(id, name.trim()) }.getOrDefault(false)
                    if (!ok) Toast.makeText(context, context.getString(R.string.common_exec_error), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { renaming = null },
        )
    }    // 创建工作区命名对话框：非空合法名称才允许进入创建流程
    if (showCreateDialog) {
        com.miniichatNext.carter.ui.components.InputDialog(
            title = stringResource(R.string.workspace_new),
            label = stringResource(R.string.common_name),
            onConfirm = { name ->
                showCreateDialog = false
                onCreateNamed(name.trim())
            },
            onDismiss = { showCreateDialog = false },
            confirmText = stringResource(R.string.action_ok),
            validator = { name ->
                when {
                    name.isBlank() -> context.getString(R.string.workspace_name_invalid)
                    name.trim() != name -> context.getString(R.string.workspace_name_invalid)
                    workspaces.any { it.name == name.trim() } -> context.getString(R.string.workspace_name_exists)
                    else -> null
                }
            },
        )
    }

    // 删除确认对话框（用ConfirmDeleteDialog通用组件）
    pendingDelete?.let { target ->
        com.miniichatNext.carter.ui.components.ConfirmDeleteDialog(
            targetName = target.name,
            typeLabel = stringResource(R.string.item_workspace),
            onConfirm = {
                val id = target.id
                pendingDelete = null
                scope.launch {
                    val ok = runCatching { repository.delete(id) }.getOrDefault(false)
                    if (!ok) Toast.makeText(context, context.getString(R.string.common_exec_error), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDetailScreen(
    repository: WorkspaceRepository,
    workspaceId: String,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaces by repository.workspacesFlow.collectAsState(initial = emptyList())
    val workspace = workspaces.firstOrNull { it.id == workspaceId }

    var rootfsUrl by remember { mutableStateOf(DEFAULT_ROOTFS_URL) }
    // 安装任务由RootfsInstallManager进程级持有：离开页面不中断，回来还能接上进度
    val installStates by RootfsInstallManager.states.collectAsState()
    val installState = installStates[workspaceId]
    val busy = installState is RootfsInstallState.Running
    val progress = (installState as? RootfsInstallState.Running)?.progress
    var command by remember { mutableStateOf("") }
    var terminalOut by remember { mutableStateOf("") }

    LaunchedEffect(installState) {
        when (installState) {
            is RootfsInstallState.Success -> {
                Toast.makeText(context, context.getString(R.string.workspace_install_done), Toast.LENGTH_SHORT).show()
                RootfsInstallManager.clearFinished(workspaceId)
            }
            is RootfsInstallState.Failure -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.workspace_install_failed, installState.message),
                    Toast.LENGTH_LONG
                ).show()
                RootfsInstallManager.clearFinished(workspaceId)
            }
            is RootfsInstallState.Cancelled -> RootfsInstallManager.clearFinished(workspaceId)
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .imePadding()
    ) {
        SettingsTopBar(
            title = workspace?.name ?: stringResource(R.string.workspace_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Linux环境卡片（rootfs安装；不再含"修复APT"按钮，由RootfsPatcher.ensureAptLocks每次shell前自动清lock）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.workspace_env_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        statusLabel(context, workspace?.shellStatus ?: WorkspaceShellStatus.DISABLED.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val isReady = workspace?.shellStatus?.equals(WorkspaceShellStatus.READY.name) == true
                    if (!isReady) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = rootfsUrl,
                            onValueChange = { rootfsUrl = it },
                            label = { Text(stringResource(R.string.workspace_rootfs_url)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && rootfsUrl.isNotBlank(),
                                onClick = {
                                    RootfsInstallManager.start(repository, workspaceId, rootfsUrl.trim())
                                }
                            ) { Text(if (busy) stringResource(R.string.workspace_installing) else stringResource(R.string.workspace_install)) }
                        }
                    } else {
                        // 已安装：默认只显示状态；提供"重新安装"折叠入口
                        var showReinstall by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { showReinstall = !showReinstall }) {
                            Text(stringResource(R.string.workspace_reinstall))
                        }
                        if (showReinstall) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = rootfsUrl,
                                onValueChange = { rootfsUrl = it },
                                label = { Text(stringResource(R.string.workspace_rootfs_url)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                enabled = !busy && rootfsUrl.isNotBlank(),
                                onClick = {
                                    RootfsInstallManager.start(repository, workspaceId, rootfsUrl.trim())
                                }
                            ) { Text(if (busy) stringResource(R.string.workspace_installing) else stringResource(R.string.workspace_install)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    progress?.let { p ->
                        Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.workspace_terminal_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
                    TextButton(
                        onClick = onOpenTerminal,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.workspace_terminal_open))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.workspace_shell_compat_mode),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        androidx.compose.material3.Switch(
                            checked = workspace?.shellCompatibilityMode == true,
                            onCheckedChange = { on ->
                                scope.launch { repository.updateShellCompatibilityMode(workspaceId, on) }
                            },
                        )
                    }
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = command,
                                onValueChange = { command = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace,
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
                                },
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
                                            repository.executeCommand(workspaceId, cmd, "", 600_000L)
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
                                            onFailure = { context.getString(R.string.common_exec_error, it.message ?: "") },
                                        )
                                    }
                                    terminalOut = out
                                }
                            }
                        ) { Text(stringResource(R.string.workspace_run)) }
                    }
                    if (terminalOut.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            terminalOut,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.workspace_approval_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 202610162134：审批按三类归并（读取/写入/执行），存储键category:NAME
                    val overrides = workspace?.toolApprovalOverrides().orEmpty()
                    listOf(
                        Triple(
                            WorkspaceToolCategory.READ_FILE,
                            stringResource(R.string.workspace_cat_read),
                            stringResource(R.string.workspace_cat_read_desc),
                        ),
                        Triple(
                            WorkspaceToolCategory.WRITE_FILE,
                            stringResource(R.string.workspace_cat_write),
                            stringResource(R.string.workspace_cat_write_desc),
                        ),
                        Triple(
                            WorkspaceToolCategory.EXECUTE_FILE,
                            stringResource(R.string.workspace_cat_execute),
                            stringResource(R.string.workspace_cat_execute_desc),
                        ),
                    ).forEach { (cat, label, desc) ->
                        val key = cat.storageKey()
                        val current = overrides[key] ?: WorkspaceCategoryDefaultApprovals[cat] ?: false
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = current,
                                onCheckedChange = { value ->
                                    scope.launch {
                                        repository.setToolApproval(workspaceId, key, value)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun statusLabel(context: android.content.Context, status: String): String = when (status) {
    WorkspaceShellStatus.READY.name -> context.getString(R.string.workspace_status_ready)
    WorkspaceShellStatus.INSTALLING.name -> context.getString(R.string.workspace_status_installing)
    WorkspaceShellStatus.BROKEN.name -> context.getString(R.string.workspace_status_broken)
    else -> context.getString(R.string.workspace_status_missing)
}

private suspend fun openFileWithExternalApp(
    context: android.content.Context,
    repository: WorkspaceRepository,
    workspaceId: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    // 导出与FileProvider URI构造在IO线程；startActivity/Toast必须回主线程
    runCatching {
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "external_open").apply { mkdirs() }
            val tmp = File(cacheDir, "${System.currentTimeMillis()}_${path.substringAfterLast('/').ifBlank { "file" }}")
            repository.exportFile(workspaceId, area, path, tmp.outputStream())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", tmp,
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }.fold(
        onSuccess = { intent -> context.startActivity(intent) },
        onFailure = {
            Toast.makeText(context, context.getString(R.string.workspace_file_open_failed, it.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    )
}
package com.miniichatNext.carter.ui.mcp

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.data.mcp.BuiltInFileTools
import com.miniichatNext.carter.data.mcp.McpClient
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.data.mcp.McpTransport
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.launch

/** MCP服务器列表 */
@Composable
fun McpServersScreen(
    mcpStore: McpStore,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenServerDetail: (String) -> Unit,
    onOpenBuiltInTools: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by mcpStore.serversFlow.collectAsState(initial = emptyList())
    val tools by mcpStore.toolsFlow.collectAsState(initial = emptyList())
    var pendingDelete by remember { mutableStateOf<McpServerConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.mcp_title), onBack)
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    BuiltInToolsEntryCard(
                        builtInToolsCount = BuiltInFileTools.schema.size,
                        onClick = onOpenBuiltInTools
                    )
                }

                item {
                    if (!hasAllFilesAccess()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching {
                                        val intent = android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }.onFailure {
                                        Toast.makeText(context, context.getString(R.string.mcp_open_settings_failed), Toast.LENGTH_LONG).show()
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    stringResource(R.string.mcp_all_files_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    stringResource(R.string.mcp_all_files_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.mcp_external_servers),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.mcp_count, servers.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (servers.isEmpty()) {
                    item { EmptyMcpServersCard(onAdd = onAdd) }
                } else {
                    items(servers, key = { it.id }) { server ->
                        McpServerItemCard(
                            server = server,
                            toolCount = tools.count { it.serverId == server.id },
                            onClick = { onOpenServerDetail(server.id) },
                            onToggleEnabled = { enabled ->
                                scope.launch { mcpStore.upsertServer(server.copy(enabled = enabled)) }
                            },
                            onDelete = { pendingDelete = server }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mcp_add)) }
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.mcp_delete_confirm_title)) },
            text = { Text(stringResource(R.string.mcp_delete_confirm_message, server.name.ifBlank { server.url })) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        mcpStore.deleteServer(server.id)
                        Toast.makeText(context, context.getString(R.string.mcp_deleted), Toast.LENGTH_SHORT).show()
                    }
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** Android 11+ 需要“所有文件访问”才能用File API读写 /storage/emulated/0 */
@Suppress("NewApi")
private fun hasAllFilesAccess(): Boolean =
    if (android.os.Build.VERSION.SDK_INT < 30) true
    else runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

@Composable
private fun BuiltInToolsEntryCard(builtInToolsCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.mcp_builtin_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.mcp_builtin_subtitle, builtInToolsCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.manage), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun EmptyMcpServersCard(onAdd: () -> Unit) {
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
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.mcp_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.mcp_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.mcp_add))
            }
        }
    }
}

@Composable
private fun McpServerItemCard(
    server: McpServerConfig,
    toolCount: Int,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    .background(
                        if (server.enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = if (server.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${if (server.tp() == McpTransport.STREAMABLE_HTTP) "HTTP" else "SSE"} · "
                        + stringResource(R.string.mcp_tools_count, toolCount)
                        + " · ${server.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = server.enabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 内置工具权限配置 */
@Composable
fun BuiltInToolsScreen(
    mcpStore: McpStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val perms by mcpStore.permsFlow.collectAsState(initial = emptyList())
    val allTools = remember { BuiltInFileTools.schema }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.mcp_builtin_config_title), onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.mcp_builtin_config_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(allTools, key = { it.name }) { tool ->
                ToolPermissionCard(
                    tool = tool,
                    perm = perms.firstOrNull { it.toolName == tool.name && it.serverId.isBlank() },
                    onToggleEnabled = { enabled ->
                        val cur = perms.firstOrNull { it.toolName == tool.name && it.serverId.isBlank() }
                        scope.launch {
                            mcpStore.setToolPermission("", tool.name, enabled, cur?.requireApproval ?: true)
                        }
                    },
                    onToggleApproval = { approval ->
                        val cur = perms.firstOrNull { it.toolName == tool.name && it.serverId.isBlank() }
                        scope.launch {
                            mcpStore.setToolPermission("", tool.name, cur?.enabled ?: true, approval)
                        }
                    }
                )
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

/** 单个MCP服务器详情：连接信息 + 逐工具权限 */
@Composable
fun McpServerDetailScreen(
    mcpStore: McpStore,
    serverId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by mcpStore.serversFlow.collectAsState(initial = emptyList())
    val tools by mcpStore.toolsFlow.collectAsState(initial = emptyList())
    val perms by mcpStore.permsFlow.collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var refreshing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var showHeaders by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = server?.name ?: stringResource(R.string.mcp_server_name),
            onBack = onBack,
            actions = {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                }
                IconButton(
                    onClick = {
                        val srv = server ?: return@IconButton
                        refreshing = true
                        scope.launch {
                            runCatching {
                                val fetched = McpClient().listTools(srv)
                                mcpStore.replaceToolsForServer(srv.id, fetched)
                                mcpStore.upsertServer(srv.copy(toolsFetched = true))
                                Toast.makeText(context, context.getString(R.string.mcp_synced, fetched.size), Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.mcp_sync_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                            refreshing = false
                        }
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.mcp_refresh_tools))
                }
                IconButton(onClick = { pendingDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
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
                    Text(stringResource(R.string.mcp_endpoint), style = MaterialTheme.typography.labelMedium)
                    Text(server?.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.mcp_transport), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (server?.tp() == McpTransport.SSE) "SSE" else "Streamable HTTP",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.mcp_tool_approval), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (server?.requireApproval == true) stringResource(R.string.mcp_yes_overridable) else stringResource(R.string.common_no),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val headers = server?.headers.orEmpty()
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.mcp_headers_count, headers.size),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (headers.isNotEmpty()) {
                            TextButton(onClick = { showHeaders = !showHeaders }) {
                                Text(if (showHeaders) stringResource(R.string.common_hide) else stringResource(R.string.common_show))
                            }
                        }
                    }
                    if (headers.isEmpty()) {
                        Text(stringResource(R.string.common_none_placeholder), style = MaterialTheme.typography.bodySmall)
                    } else if (showHeaders) {
                        headers.forEach { (k, v) ->
                            Text(
                                "$k: $v",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        headers.keys.forEach { k ->
                            Text(
                                "$k: ••••",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.mcp_tool_permissions, tools.count { it.serverId == serverId }),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            val serverTools = tools.filter { it.serverId == serverId }
            if (serverTools.isEmpty()) {
                Text(
                    stringResource(R.string.mcp_not_synced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                serverTools.forEach { tool ->
                    ToolPermissionCard(
                        tool = tool,
                        perm = perms.firstOrNull { it.toolName == tool.name && it.serverId == serverId },
                        onToggleEnabled = { enabled ->
                            val cur = perms.firstOrNull { it.toolName == tool.name && it.serverId == serverId }
                            scope.launch {
                                mcpStore.setToolPermission(serverId, tool.name, enabled, cur?.requireApproval ?: true)
                            }
                        },
                        onToggleApproval = { approval ->
                            val cur = perms.firstOrNull { it.toolName == tool.name && it.serverId == serverId }
                            scope.launch {
                                mcpStore.setToolPermission(serverId, tool.name, cur?.enabled ?: true, approval)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (pendingDelete) {
        val name = server?.name?.ifBlank { server.url } ?: ""
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.mcp_delete_confirm_title)) },
            text = { Text(stringResource(R.string.mcp_delete_confirm_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    scope.launch {
                        mcpStore.deleteServer(serverId)
                        Toast.makeText(context, context.getString(R.string.mcp_deleted), Toast.LENGTH_SHORT).show()
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/**
 * MCP服务器新增 / 编辑。
 * 请求头按RikkaHub的做法逐行编辑（名称 + 值 + 删除），值默认以密码形式显示，可切换明文。
 */
@Composable
fun McpServerEditorScreen(
    initial: McpServerConfig?,
    onBack: () -> Unit,
    onSaved: (McpServerConfig) -> Unit,
    onSyncNow: suspend (McpServerConfig) -> Result<Int>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var url by remember(initial?.id) { mutableStateOf(initial?.url ?: "") }
    var transport by remember(initial?.id) {
        mutableStateOf(initial?.tp() ?: McpTransport.STREAMABLE_HTTP)
    }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    var requireApproval by remember(initial?.id) { mutableStateOf(initial?.requireApproval ?: true) }
    // 请求头按行编辑：保持顺序，保存时再折成map
    val headerRows = remember(initial?.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.headers.orEmpty().entries.map { it.key to it.value })
        }
    }
    var saving by remember { mutableStateOf(false) }

    val canSave = url.isNotBlank() && !saving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = if (initial == null) stringResource(R.string.mcp_add) else stringResource(R.string.mcp_edit),
            onBack = onBack,
            actions = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val merged = linkedMapOf<String, String>()
                        headerRows.forEach { (k, v) -> if (k.isNotBlank()) merged[k.trim()] = v.trim() }
                        val entity = (initial ?: McpServerConfig(
                            id = newId(),
                            name = "",
                            url = "",
                        )).copy(
                            name = name.trim().ifBlank { url.trim() },
                            url = url.trim(),
                            transport = transport.name,
                            enabled = enabled,
                            requireApproval = requireApproval,
                            headers = merged,
                        )
                        saving = true
                        onSaved(entity)
                        scope.launch {
                            onSyncNow(entity)
                                .onSuccess { count ->
                                    Toast.makeText(context, context.getString(R.string.mcp_synced, count), Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, context.getString(R.string.mcp_sync_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                                }
                            saving = false
                        }
                    }
                ) { Text(stringResource(R.string.save)) }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.mcp_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.mcp_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                placeholder = {
                    Text(
                        if (transport == McpTransport.SSE) "http://host:port/sse"
                        else "http://host:port/mcp"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(stringResource(R.string.mcp_transport), style = MaterialTheme.typography.labelLarge)
            McpTransport.entries.forEach { t ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { transport = t }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(selected = transport == t, onClick = { transport = t })
                    Text(if (t == McpTransport.SSE) stringResource(R.string.mcp_transport_sse) else stringResource(R.string.mcp_transport_http))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mcp_enabled), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.mcp_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mcp_require_approval), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.mcp_require_approval_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = requireApproval, onCheckedChange = { requireApproval = it })
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.mcp_headers), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.mcp_headers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            headerRows.forEachIndexed { index, header ->
                var valueVisible by remember(initial?.id, index) { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = header.first,
                            onValueChange = { v -> headerRows[index] = v to headerRows[index].second },
                            label = { Text(stringResource(R.string.mcp_header_name)) },
                            singleLine = true,
                            placeholder = { Text("Authorization") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = header.second,
                            onValueChange = { v -> headerRows[index] = headerRows[index].first to v },
                            label = { Text(stringResource(R.string.mcp_header_value)) },
                            singleLine = true,
                            placeholder = { Text("Bearer xxx") },
                            visualTransformation = if (valueVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { valueVisible = !valueVisible }) {
                                    Icon(
                                        if (valueVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (valueVisible) stringResource(R.string.common_hide) else stringResource(R.string.common_show)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    IconButton(onClick = { headerRows.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.mcp_header_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Button(
                onClick = { headerRows.add("" to "") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.mcp_header_add))
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ToolPermissionCard(
    tool: McpTool,
    perm: McpToolPermission?,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleApproval: (Boolean) -> Unit
) {
    val enabled = perm?.enabled ?: true
    val requireApproval = perm?.requireApproval ?: true

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                tool.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tool.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mcp_tool_enable), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mcp_tool_approval), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = requireApproval, onCheckedChange = onToggleApproval, enabled = enabled)
            }
        }
    }
}

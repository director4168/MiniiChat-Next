package com.miniichatNext.carter.ui.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.tools.BuiltInFileTools
import com.miniichatNext.carter.data.tools.SpecialTools
import com.miniichatNext.carter.data.workspace.WORKSPACE_SERVER_ID
import com.miniichatNext.carter.data.workspace.WorkspaceToolDefaultApprovals
import com.miniichatNext.carter.data.workspace.workspaceToolSpecs
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.ui.components.ToolPermissionCard
import com.miniichatNext.carter.ui.navigation.AppBackDispatcher
import com.miniichatNext.carter.ui.navigation.NavBackInterceptor
import kotlinx.coroutines.launch

/** 内置工具权限配置 */
@Composable
fun BuiltInToolsScreen(
    mcpStore: McpStore,
    onBack: () -> Unit,
    backDispatcher: AppBackDispatcher? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val perms by mcpStore.permsFlow.collectAsState(initial = emptyList())
    // 内置工具+工作区工具合并展示（工作区工具由WorkspaceTools提供spec/schema）
    val allTools = remember {
        BuiltInFileTools.schema(context) + workspaceToolSpecs()
    }

    // 分组：组名资源ID→(介绍资源ID,工具名集合)
    val groups = remember(allTools) {
        listOf(
            Triple(
                R.string.builtin_group_ask, R.string.builtin_group_ask_desc,
                listOf("interactive_clarification")
            ),
            Triple(
                R.string.builtin_group_shell, R.string.builtin_group_shell_desc,
                listOf("execute_shell")
            ),
            Triple(
                R.string.builtin_group_file_io, R.string.builtin_group_file_io_desc,
                listOf("file_read", "file_write", "file_edit", "file_append")
            ),
            Triple(
                R.string.builtin_group_file_manage, R.string.builtin_group_file_manage_desc,
                listOf("file_list", "file_delete", "file_move", "file_copy", "file_rename", "file_mkdir")
            ),
            Triple(
                R.string.builtin_group_search, R.string.builtin_group_search_desc,
                listOf("file_search", "file_exists", "file_info")
            ),
            Triple(
                R.string.builtin_group_workspace, R.string.builtin_group_workspace_desc,
                listOf("workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file",
                       "workspace_list_files", "workspace_delete_file", "workspace_move_file",
                       "workspace_copy_file", "workspace_mkdir", "workspace_glob", "workspace_grep")
            ),
            Triple(
                R.string.builtin_group_web, R.string.builtin_group_web_desc,
                listOf("browser_fetch")
            ),
            Triple(
                R.string.builtin_group_special, R.string.builtin_group_special_desc,
                SpecialTools.names
            ),
        )
    }
    var openGroupRes by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }

    // 权限状态：所有文件访问要在系统设置里开，日历权限走运行时申请
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    var calendarGranted by remember { mutableStateOf(hasCalendarAccess(context)) }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { allFilesGranted = hasAllFilesAccess() }
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { calendarGranted = hasCalendarAccess(context) }
    val openAllFilesSettings: () -> Unit = {
        runCatching {
            allFilesLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.mcp_open_settings_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    // 子页面打开时拦截系统返回手势：先关子页面回组列表，而不是直接退出内置工具页
    if (backDispatcher != null) {
        NavBackInterceptor(
            dispatcher = backDispatcher,
            route = "McpBuiltIn",
            enabled = openGroupRes != null,
            onBack = { openGroupRes = null; query = ""; true },
        )
    }

    val groupTools = openGroupRes?.let { res ->
        groups.firstOrNull { it.first == res }?.third
            ?.mapNotNull { name -> allTools.firstOrNull { it.name == name } }
            .orEmpty()
    }
    val filteredTools = groupTools?.filter { tool ->
        query.isBlank() ||
            tool.name.contains(query, ignoreCase = true) ||
            tool.description.contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = openGroupRes?.let { stringResource(it) } ?: stringResource(R.string.mcp_builtin_config_title),
            onBack = { if (openGroupRes != null) { openGroupRes = null; query = "" } else onBack() }
        )
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

            if (openGroupRes == null && !allFilesGranted) {
                item {
                    PermissionWarningCard(
                        title = stringResource(R.string.mcp_all_files_title),
                        message = stringResource(R.string.mcp_all_files_message),
                        onClick = openAllFilesSettings,
                    )
                }
            }

            if (openGroupRes == null) {
                items(groups, key = { it.first }) { (res, descRes, names) ->
                    val title = stringResource(res)
                    val desc = stringResource(descRes)
                    // 自动计数：组内名字在allTools（内置+工作区）里实际存在的数量
                    val count = names.count { name -> allTools.any { it.name == name } }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openGroupRes = res },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.mcp_builtin_group_count, count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // 设备工具只需要提示日历权限（所有文件访问在列表页提示，避免重复）
                if (openGroupRes == R.string.builtin_group_special && !calendarGranted) {
                    item {
                        PermissionWarningCard(
                            title = stringResource(R.string.mcp_calendar_permission_title),
                            message = stringResource(R.string.mcp_calendar_permission_message),
                            onClick = {
                                calendarLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR,
                                    )
                                )
                            },
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.mcp_builtin_search_hint)) },
                    )
                }
                items(filteredTools.orEmpty(), key = { it.name }) { tool ->
                    // 工作区工具serverId="workspace"（非空）——perm查写必须按各自serverId，否则永远查不到/写串轨
                    val sid = if (tool.serverId.isNotBlank()) tool.serverId else ""
                    val defaultApproval = if (tool.serverId == WORKSPACE_SERVER_ID)
                        WorkspaceToolDefaultApprovals[tool.name] ?: false
                    else BuiltInFileTools.defaultRequireApproval(tool.name)
                    ToolPermissionCard(
                        tool = tool,
                        perm = perms.firstOrNull { it.toolName == tool.name && it.serverId == sid },
                        defaultApproval = defaultApproval,
                        showApproval = sid != WORKSPACE_SERVER_ID,
                        onToggleEnabled = { enabled ->
                            val cur = perms.firstOrNull { it.toolName == tool.name && it.serverId == sid }
                            scope.launch {
                                mcpStore.setToolPermission(
                                    sid, tool.name, enabled,
                                    cur?.requireApproval ?: defaultApproval
                                )
                            }
                        },
                        onToggleApproval = { approval ->
                            val cur = perms.firstOrNull { it.toolName == tool.name && it.serverId == sid }
                            scope.launch {
                                mcpStore.setToolPermission(sid, tool.name, cur?.enabled ?: true, approval)
                            }
                        }
                    )
                }
                if (filteredTools?.isEmpty() == true) {
                    item {
                        Text(
                            stringResource(R.string.mcp_builtin_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}
/** 权限未授予时的提示卡，点击后去申请 */
@Composable
private fun PermissionWarningCard(title: String, message: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Android 11+需要“所有文件访问”才能用File API读写/storage/emulated/0 */
@Suppress("NewApi")
private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT < 30) true
    else runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

/** 日历工具需要读写日历权限 */
private fun hasCalendarAccess(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

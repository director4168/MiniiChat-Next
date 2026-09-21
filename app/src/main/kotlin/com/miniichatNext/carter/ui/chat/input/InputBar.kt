package com.miniichatNext.carter.ui.chat.input

import android.net.Uri
import android.widget.Toast
import com.miniichatNext.carter.util.AttachmentImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.workspace.WorkspaceEntity
import com.miniichatNext.carter.data.workspace.WorkspaceShellStatus
import com.miniichatNext.carter.util.AttachmentLoader

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<Attachment>,
    onAttachmentsChange: (List<Attachment>) -> Unit,
    modelLabel: String,
    onPickModel: () -> Unit,
    skills: List<Skill>,
    enabledSkillIds: Set<String>,
    onToggleSkill: (String, Boolean) -> Unit,
    onContextCompress: () -> Unit,
    // 对话级工具开关（与「助手技能」同一套交互：弹窗里逐个开关）
    workspaces: List<WorkspaceEntity> = emptyList(),
    effectiveWorkspaceId: String? = null,
    onPickWorkspace: (String?) -> Unit = {},
    mcpServers: List<McpServerConfig> = emptyList(),
    effectiveMcpServerIds: Set<String> = emptySet(),
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> },
    onResetToolOverrides: () -> Unit = {},
    onManageWorkspaces: () -> Unit = {},
    onManageMcp: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean = true,
    // CSS/图片背景模式下让输入栏容器透明
    transparent: Boolean = false,
    // 菜单面板，开关状态由ChatScreen持有，方便点空白处统一收起
    addPanelOpen: Boolean = false,
    onToggleAddPanel: () -> Unit = {},
    // 候选回复：内容由ChatScreen以slot形式传入，位置/动画与菜单面板同一套
    candidatesOpen: Boolean = false,
    onToggleCandidates: () -> Unit = {},
    candidatesPanel: (@Composable () -> Unit)? = null,
    // 键盘弹出时收起面板
    onDismissPanels: () -> Unit = {},
    // 外部请求聚焦输入框（候选回复点编辑时用）：值递增即触发一次
    focusInputRequest: Int = 0
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val inputFocus = remember { FocusRequester() }
    var skillSheetOpen by remember { mutableStateOf(false) }
    var workspaceDialogOpen by remember { mutableStateOf(false) }
    var mcpDialogOpen by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val expanded = imeVisible || addPanelOpen || candidatesOpen

    // 键盘弹出→收起面板（避免面板+键盘同时占屏把内容挤没）
    LaunchedEffect(imeVisible) {
        if (imeVisible) onDismissPanels()
    }
    // 候选回复点编辑：把内容放进输入框后聚焦并拉起键盘
    LaunchedEffect(focusInputRequest) {
        if (focusInputRequest > 0) {
            runCatching { inputFocus.requestFocus() }
            keyboard?.show()
        }
    }

    // 附件导入逻辑（suspend函数），两个picker launcher都调用它
    // 不能在lambda里直接调rememberCoroutineScope() —— 它是 @Composable，
    // 普通lambda没有 @Composable 上下文。所以把scope提到composable顶层，
    // launcher callback里scope.launch { ... } 启动协程。
    val importScope = rememberCoroutineScope()
    suspend fun doImport(picked: List<Uri>) {
        val result = withContext(Dispatchers.IO) {
            AttachmentImporter.importAll(
                context = ctx,
                uris = picked,
                existingHashes = attachments.mapNotNull { it.sha256.takeIf { h -> h.isNotBlank() } }.toSet(),
            )
        }
        val msg = buildList {
            if (result.added.isNotEmpty()) {
                add(ctx.getString(R.string.attachment_added, result.added.size))
            }
            if (result.skippedTooLarge.isNotEmpty()) {
                add(ctx.getString(
                    R.string.attachment_too_large,
                    AttachmentLoader.MAX_ATTACHMENT_BYTES / 1024 / 1024
                ) + "（${result.skippedTooLarge.size} 个）")
            }
            if (result.skippedFailed.isNotEmpty()) {
                add(ctx.getString(R.string.attachment_import_failed,
                    result.skippedFailed.size))
            }
            if (result.dedupedAgainstExisting.isNotEmpty()) {
                add(ctx.getString(R.string.attachment_duplicate,
                    result.dedupedAgainstExisting.size))
            }
        }.joinToString("；")
        if (msg.isNotBlank()) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
        if (result.added.isNotEmpty()) {
                    // 图片不截断（压缩后整张发出）—— 这里只提示实际发出去的大小，不再提"只发前N KB"
                    val biggest = result.added.maxByOrNull { it.sizeBytes }
                    if (biggest != null && biggest.type == "image" && biggest.sizeBytes > 2L * 1024 * 1024) {
                        Toast.makeText(
                            ctx,
                            ctx.getString(
                                R.string.attachment_large_inline_hint,
                                AttachmentLoader.formatBytes(biggest.sizeBytes)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onAttachmentsChange(attachments + result.added)
                }
    }

    // 多选图片：系统照片选择器（自带HEIC→JPEG兼容）
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importScope.launch { doImport(uris) }
    }
    // 多选任意文件
    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importScope.launch { doImport(uris) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.background.copy(
                    alpha = if (transparent) 0f else 1f
                )
            )
            .padding(WindowInsets.navigationBars.asPaddingValues())
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // key用Attachment自带的uuid id —— 同一个物理文件被不同picker/ContentProvider
                // 暴露可能产生不同的uri字符串，dedupe用精确字符串匹配抓不住，uri当key会崩
                items(attachments, key = { att -> att.id }) { att ->
                    AttachmentChip(att = att, onRemove = {
                        onAttachmentsChange(attachments - att)
                    })
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onPickModel),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = stringResource(R.string.select_model),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 160.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    stringResource(R.string.hint_input),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                                textStyle = LocalTextStyle.current.copy(
                                    color = LocalContentColor.current,
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences
                                ),
                                maxLines = 6,
                                enabled = enabled
                            )
                        }
                        if (!expanded) {
                            Spacer(Modifier.width(4.dp))
                            IconActionPainter(
                                painter = painterResource(R.drawable.ic_thinking_level),
                                contentDescription = stringResource(R.string.reply_candidates),
                                active = candidatesOpen,
                                enabled = enabled,
                                onClick = {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onToggleCandidates()
                                }
                            )
                            Spacer(Modifier.width(2.dp))
                            IconActionVec(
                                icon = Icons.Default.Add,
                                contentDescription = stringResource(R.string.attach),
                                active = addPanelOpen,
                                enabled = enabled,
                                onClick = {
                                    // 打开面板时收起键盘面板会接替键盘的位置
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onToggleAddPanel()
                                }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(expandFrom = Alignment.Top) +
                            fadeIn(animationSpec = androidx.compose.animation.core.tween(160)),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) +
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(120))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconActionVec(
                                icon = Icons.Default.Image,
                                contentDescription = stringResource(R.string.attach_image),
                                enabled = enabled,
                                onClick = { pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            )
                            Spacer(Modifier.weight(1f))
                            IconActionPainter(
                                painter = painterResource(R.drawable.ic_thinking_level),
                                contentDescription = stringResource(R.string.reply_candidates),
                                active = candidatesOpen,
                                enabled = enabled,
                                onClick = {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onToggleCandidates()
                                }
                            )
                            Spacer(Modifier.width(2.dp))
                            IconActionVec(
                                icon = Icons.Default.Add,
                                contentDescription = stringResource(R.string.attach),
                                active = addPanelOpen,
                                enabled = enabled,
                                onClick = {
                                    // 打开面板时收起键盘面板会接替键盘的位置
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onToggleAddPanel()
                                }
                            )
                        }
                    }

                }
            }
            Spacer(Modifier.width(6.dp))
            val canSend = (value.trim().isNotEmpty() || attachments.isNotEmpty())
                && !isStreaming && enabled
            val sendBg = when {
                isStreaming -> MaterialTheme.colorScheme.onSurface
                canSend -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val sendFg = when {
                isStreaming -> MaterialTheme.colorScheme.background
                canSend -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .clickable(enabled = isStreaming || canSend) {
                        if (isStreaming) onStop()
                        else if (canSend) {
                            keyboard?.hide()
                            focus.clearFocus()
                            onSend()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    contentDescription = if (isStreaming) "stop" else stringResource(R.string.send),
                    tint = sendFg,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = addPanelOpen,
            enter = expandVertically(expandFrom = Alignment.Bottom) +
                fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) +
                fadeOut(animationSpec = androidx.compose.animation.core.tween(140))
        ) {
            AddActionPanel(
                onPickImage = {
                    pickImages.launch(androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    ))
                },
                onPickFile = { pickFiles.launch("*/*") },
                onCompress = onContextCompress,
                onSkills = { skillSheetOpen = true },
                onWorkspace = { workspaceDialogOpen = true },
                onMcp = { mcpDialogOpen = true },
            )
        }

        AnimatedVisibility(
            visible = candidatesOpen && candidatesPanel != null,
            enter = expandVertically(expandFrom = Alignment.Bottom) +
                fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) +
                fadeOut(animationSpec = androidx.compose.animation.core.tween(140))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                candidatesPanel?.invoke()
            }
        }
    }

    if (skillSheetOpen) {
        AlertDialog(
            onDismissRequest = { skillSheetOpen = false },
            title = { Text(stringResource(R.string.assistant_skills)) },
            text = {
                if (skills.isEmpty()) {
                    Text(stringResource(R.string.skill_no_skills_for_assistant))
                } else {
                    Column {
                        skills.forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                    if (s.description.isNotBlank()) {
                                        Text(
                                            s.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Switch(
                                    checked = s.id in enabledSkillIds,
                                    onCheckedChange = { onToggleSkill(s.id, it) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { skillSheetOpen = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // 工作区：对话级选择（与「助手技能」一致的弹窗开关）
    if (workspaceDialogOpen) {
        AlertDialog(
            onDismissRequest = { workspaceDialogOpen = false },
            title = { Text("工作区") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "选择本次对话使用的工作区；都不选则本次对话不向模型提供工作区工具。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    if (workspaces.isEmpty()) {
                        Text(
                            "还没有工作区，点右下角“管理”去创建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        workspaces.forEach { ws ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ws.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        workspaceStatusLabel(ws.shellStatus),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ws.id == effectiveWorkspaceId,
                                    onCheckedChange = { on -> onPickWorkspace(if (on) ws.id else null) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { workspaceDialogOpen = false }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    workspaceDialogOpen = false
                    onManageWorkspaces()
                }) { Text("管理") }
            }
        )
    }

    // MCP：对话级开关（与「助手技能」一致的弹窗开关）
    if (mcpDialogOpen) {
        AlertDialog(
            onDismissRequest = { mcpDialogOpen = false },
            title = { Text("MCP 服务") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "选择本次对话启用的 MCP 服务；关闭后本次对话不再向模型提供该服务的工具。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    if (mcpServers.isEmpty()) {
                        Text(
                            "还没有配置 MCP 服务，点右下角“管理”去添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        mcpServers.forEach { srv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        srv.name.ifBlank { srv.url },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        srv.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = srv.id in effectiveMcpServerIds,
                                    onCheckedChange = { on -> onToggleMcpServer(srv.id, on) }
                                )
                            }
                        }
                        Spacer(Modifier.heightIn(min = 4.dp))
                        TextButton(onClick = onResetToolOverrides) {
                            Text("恢复为跟随助手设置")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mcpDialogOpen = false }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    mcpDialogOpen = false
                    onManageMcp()
                }) { Text("管理") }
            }
        )
    }
}

private fun workspaceStatusLabel(status: String): String = when (status) {
    WorkspaceShellStatus.READY.name -> "环境已就绪"
    WorkspaceShellStatus.INSTALLING.name -> "正在安装"
    WorkspaceShellStatus.BROKEN.name -> "环境损坏"
    else -> "未安装 Rootfs"
}

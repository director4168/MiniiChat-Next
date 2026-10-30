package com.miniichatNext.carter.ui.chat.input

import android.net.Uri
import android.widget.Toast
import com.miniichatNext.carter.data.util.AttachmentImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.miniichatNext.carter.data.util.AttachmentLoader
import com.miniichatNext.carter.util.AppThemeOverrides
import com.miniichatNext.carter.ui.theme.LocalDarkMode
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<Attachment>,
    onAttachmentsChange: (List<Attachment>) -> Unit,
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
    // 打开当前会话绑定工作区的交互式终端（无绑定由调用方提示）
    onOpenTerminal: () -> Unit = {},
    onManageSkills: () -> Unit = {},
    onManageWorkspaces: () -> Unit = {},
    onManageMcp: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean = true,
    /** 模型选择按钮独立可用：activeModel为空（未选过模型）时仍要能点进去选择 */
    canPickModel: Boolean = true,
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
    focusInputRequest: Int = 0,
    // 编辑模式：发送键变保存键，提交走onCommitEdit（内容已在value里）
    editing: Boolean = false,
    onCommitEdit: (String) -> Unit = {},
    onCancelEdit: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val inputFocus = remember { FocusRequester() }
    var skillSheetOpen by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var workspaceDialogOpen by remember { mutableStateOf(false) }
    var mcpDialogOpen by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

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
    // 不能在lambda里直接调rememberCoroutineScope()——它是@Composable，
    // 普通lambda没有@Composable上下文。所以把scope提到composable顶层，
    // launcher callback里scope.launch { ... }启动协程。
    val importScope = rememberCoroutineScope()
    val latestAttachments by rememberUpdatedState(attachments)
    val latestOnAttachmentsChange by rememberUpdatedState(onAttachmentsChange)
    val importMutex = remember { kotlinx.coroutines.sync.Mutex() }
    suspend fun doImport(picked: List<Uri>) = importMutex.withLock {
        val ownerAtStart = latestAttachments.map { it.id }
        val result = withContext(Dispatchers.IO) {
            AttachmentImporter.importAll(
                context = ctx,
                uris = picked,
                existingHashes = latestAttachments.mapNotNull { it.sha256.takeIf { h -> h.isNotBlank() } }.toSet(),
            )
        }
        val msg = buildList {
            if (result.added.isNotEmpty()) {
                add(ctx.getString(R.string.attachment_added, result.added.size))
            }
            if (result.skippedTooLarge.isNotEmpty()) {
                add(ctx.getString(
                    R.string.attachment_too_large_counted,
                    AttachmentLoader.MAX_ATTACHMENT_BYTES / 1024 / 1024,
                    result.skippedTooLarge.size
                ))
            }
            if (result.skippedFailed.isNotEmpty()) {
                add(ctx.getString(R.string.attachment_import_failed,
                    result.skippedFailed.size))
            }
            if (result.dedupedAgainstExisting.isNotEmpty()) {
                add(ctx.getString(R.string.attachment_duplicate,
                    result.dedupedAgainstExisting.size))
            }
        }.joinToString(ctx.getString(R.string.common_note_separator))
        if (msg.isNotBlank()) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
        if (result.added.isNotEmpty()) {
                    // 图片不截断（压缩后整张发出）——这里只提示实际发出去的大小，不再提"只发前N KB"
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
                    val latest = latestAttachments
                    if (latest.map { it.id } == ownerAtStart) {
                        latestOnAttachmentsChange(latest + result.added)
                    } else {
                        // 导入期间附件列表发生变化，合并到最新列表而不是用过期快照覆盖。
                        val hashes = latest.mapNotNull { it.sha256.takeIf(String::isNotBlank) }.toSet()
                        val merged = result.added.filter { it.sha256 !in hashes }
                        latestOnAttachmentsChange(latest + merged)
                    }
                }
    }

    // 多选图片：系统照片选择器（自带HEIC→JPEG兼容）
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importScope.launch { doImport(uris) }
    }
    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importScope.launch { doImport(uris) }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
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
        if (editing) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.input_editing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (transparent) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // key用Attachment自带的uuid id——同一物理文件被不同picker/ContentProvider
                        // 暴露可能产生不同uri字符串，uri当key会撞
                        items(attachments, key = { att -> att.id }) { att ->
                            AttachmentChip(att = att, onRemove = {
                                onAttachmentsChange(attachments - att)
                            })
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp, max = 200.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.hint_input),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp)
                            .focusRequester(inputFocus),
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

                // 底部功能键行，焊死在容器底部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconActionVec(
                            icon = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.select_model),
                            enabled = canPickModel,
                            onClick = onPickModel
                        )
                        IconActionVec(
                            icon = Icons.Default.Image,
                            contentDescription = stringResource(R.string.attach_image),
                            enabled = enabled,
                            onClick = { pickImages.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            ) }
                        )
                        IconActionVec(
                            icon = Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.input_fullscreen_edit),
                            enabled = enabled,
                            onClick = { fullScreen = true }
                        )
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
                    }

                    IconActionVec(
                        icon = Icons.Default.Add,
                        contentDescription = stringResource(R.string.attach),
                        active = addPanelOpen,
                        enabled = enabled,
                        onClick = {
                            // 打开面板时收起键盘，面板会接替键盘的位置
                            keyboard?.hide()
                            focus.clearFocus()
                            onToggleAddPanel()
                        }
                    )
                    Spacer(Modifier.width(4.dp))

                    val canSend = if (editing) value.trim().isNotEmpty() && enabled
                        else (value.trim().isNotEmpty() || attachments.isNotEmpty()) && !isStreaming && enabled
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
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(sendBg)
                            .clickable(enabled = isStreaming || canSend) {
                                if (editing) {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onCommitEdit(value)
                                } else if (isStreaming) onStop()
                                else if (canSend) {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onSend()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isStreaming -> Icons.Default.Stop
                                editing -> Icons.Default.Save
                                else -> Icons.Default.ArrowUpward
                            },
                            contentDescription = when {
                                isStreaming -> "stop"
                                editing -> stringResource(R.string.action_save)
                                else -> stringResource(R.string.send)
                            },
                            tint = sendFg,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
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
                onTerminal = onOpenTerminal,
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

    // 全屏编辑：大屏多行编辑器（内容与主输入框同源），右上发送/保存键行为与主输入框一致
    // 全屏编辑层：条件渲染覆盖层（不用Dialog——Dialog窗口的IME insets在低API不可靠，
    // 且脱离了ChatScreen的imePadding生态；作为Box兄弟层则自然继承主界面的键盘跟随）
    if (fullScreen) {
        val fsFocus = remember { FocusRequester() }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.statusBars.asPaddingValues())
            ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { fullScreen = false }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                        Text(
                            stringResource(if (editing) R.string.input_editing_hint else R.string.hint_input),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val fsCanSend = if (editing) value.trim().isNotEmpty() && enabled
                            else (value.trim().isNotEmpty() || attachments.isNotEmpty()) && !isStreaming && enabled
                        TextButton(
                            enabled = if (editing) value.trim().isNotEmpty() else (isStreaming || fsCanSend),
                            onClick = {
                                if (editing) {
                                    onCommitEdit(value)
                                    fullScreen = false
                                } else if (isStreaming) onStop()
                                else if (fsCanSend) {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    onSend()
                                    fullScreen = false
                                }
                            }
                        ) {
                            Text(stringResource(
                                if (isStreaming) R.string.stop
                                else if (editing) R.string.action_save
                                else R.string.send
                            ))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            // fillMaxWidth（不能fillMaxSize）：字段高度须随内容增长，
                            // 外部verticalScroll才有滚动范围；fillMaxSize会把字段钉死在视口高，
                            // 底部内容只进内部光标滚动，徒手滑不到底
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .focusRequester(fsFocus),
                            textStyle = LocalTextStyle.current.copy(
                                color = LocalContentColor.current,
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            enabled = enabled
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { fsFocus.requestFocus() } }
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
                Row {
                    TextButton(onClick = {
                        skillSheetOpen = false
                        onManageSkills()
                    }) {
                        Text(stringResource(R.string.manage))
                    }
                    TextButton(onClick = { skillSheetOpen = false }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        )
    }

    // 工作区：对话级选择（与「助手技能」一致的弹窗开关）
    if (workspaceDialogOpen) {
        AlertDialog(
            onDismissRequest = { workspaceDialogOpen = false },
            title = { Text(stringResource(R.string.workspace_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.workspace_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    if (workspaces.isEmpty()) {
                        Text(
                            stringResource(R.string.workspace_dialog_empty),
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
                                        color = workspaceStatusColor(ws.shellStatus)
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
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    workspaceDialogOpen = false
                    onManageWorkspaces()
                }) { Text(stringResource(R.string.manage)) }
            }
        )
    }

    // MCP：对话级开关（与「助手技能」一致的弹窗开关）
    if (mcpDialogOpen) {
        AlertDialog(
            onDismissRequest = { mcpDialogOpen = false },
            title = { Text(stringResource(R.string.mcp_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.mcp_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.heightIn(min = 8.dp))
                    if (mcpServers.isEmpty()) {
                        Text(
                            stringResource(R.string.mcp_dialog_empty),
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
                            Text(stringResource(R.string.tool_override_reset))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mcpDialogOpen = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    mcpDialogOpen = false
                    onManageMcp()
                }) { Text(stringResource(R.string.manage)) }
            }
        )
    }
}

@Composable
private fun workspaceStatusLabel(status: String): String = when (status) {
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_status_ready)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_status_installing)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_status_broken)
    else -> stringResource(R.string.workspace_status_missing)
}

@Composable
private fun workspaceStatusColor(status: String): Color {
    val context = LocalContext.current
    val dark = LocalDarkMode.current
    val overrides = remember(AppThemeOverrides.version.intValue, dark) {
        AppThemeOverrides.load(context)[if (dark) "dark" else "light"].orEmpty()
    }
    return when (status) {
        WorkspaceShellStatus.BROKEN.name -> AppThemeOverrides.customColor(
            overrides, "workspaceDanger", MaterialTheme.colorScheme.error
        )
        WorkspaceShellStatus.DISABLED.name, WorkspaceShellStatus.INSTALLING.name ->
            AppThemeOverrides.customColor(
                overrides,
                "workspaceWarning",
                if (dark) Color(0xFFFFCA5C) else Color(0xFF8A5A00),
            )
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

package com.miniichatNext.carter.ui.chat.input

import android.net.Uri
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.skills.Skill
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
    val imeVisible = WindowInsets.isImeVisible
    // 二级容器在这些情况下展开
    val expanded = imeVisible || addPanelOpen || candidatesOpen

    // 键盘弹出→收起面板（避免面板+键盘同时占屏把内容挤没）
    LaunchedEffect(imeVisible) {
        if (imeVisible) onDismissPanels()
    }
    // 候选回复点编辑"：把内容放进输入框后聚焦并拉起键盘
    LaunchedEffect(focusInputRequest) {
        if (focusInputRequest > 0) {
            runCatching { inputFocus.requestFocus() }
            keyboard?.show()
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            if (size in 1..AttachmentLoader.MAX_ATTACHMENT_BYTES || size <= 0) {
                ctx.contentResolver.runCatching {
                    takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                onAttachmentsChange(attachments + Attachment(
                    type = "image", uri = uri.toString(),
                    mimeType = mime, name = name, sizeBytes = size
                ))
            } else {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.attachment_too_large,
                        AttachmentLoader.MAX_ATTACHMENT_BYTES / 1024 / 1024),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            if (size in 1..AttachmentLoader.MAX_ATTACHMENT_BYTES || size <= 0) {
                ctx.contentResolver.runCatching {
                    takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val isImage = mime.startsWith("image/")
                onAttachmentsChange(attachments + Attachment(
                    type = if (isImage) "image" else "file",
                    uri = uri.toString(),
                    mimeType = mime, name = name, sizeBytes = size
                ))
            } else {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.attachment_too_large,
                        AttachmentLoader.MAX_ATTACHMENT_BYTES / 1024 / 1024),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
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
                items(attachments, key = { it.uri }) { att ->
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
            // 模型选择
            Box(
                // 顶部让出容器内边距(8dp)+首行定高36dp，使圆球中心与输入框中心水平对齐
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

            // 输入容器
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // 第一行文本；未展开第二行时候选回复/加号内联在右侧
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

                    // 第二行键盘弹出/面板展开时出现
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
                                onClick = { pickImage.launch("image/*") }
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
            // 发送/停止（保持在容器外）
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
                onPickImage = { pickImage.launch("image/*") },
                onPickFile = { pickFile.launch("*/*") },
                onCompress = onContextCompress,
                onSkills = { skillSheetOpen = true }
            )
        }

        // 候选回复面板
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
}

/** 容器内的图标按钮：候选回复/加号菜单按钮/图片选择共用 */

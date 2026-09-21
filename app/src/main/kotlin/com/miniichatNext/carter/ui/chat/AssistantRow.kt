package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.ui.components.AvatarView

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AssistantRow(
    message: Message,
    avatar: com.miniichatNext.carter.data.avatar.Avatar,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    editing: Boolean = false,
    editingDraft: String = "",
    onEditingDraftChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onContinue: () -> Unit = {},
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit = {},
    onApproveTool: ((com.miniichatNext.carter.data.model.ToolInvocation, String?) -> Unit)? = null,
    onRejectTool: ((com.miniichatNext.carter.data.model.ToolInvocation) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 助手消息头像：渲染真实助手头像
            com.miniichatNext.carter.ui.components.AvatarView(
                avatar = avatar,
                fallbackInitial = "M",
                size = 22.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                senderLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(8.dp))
        if (editing) {
            // 仅对助手消息可编辑
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(12.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = editingDraft,
                    onValueChange = onEditingDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                        color = Color(0xFFECECEC),
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TextButton(onClick = onCancelEdit) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.TextButton(onClick = onCommitEdit) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        } else {
            // 思考过程 + 工具调用：RikkaHub风格的时间线卡片，放在回复正文之前
            val clarifications = message.toolInvocations.filter { isClarificationTool(it.toolName) }
            val steps = buildList {
                message.reasoningContent?.takeIf { it.isNotBlank() }
                    ?.let { add(ThoughtStep.Reasoning(it)) }
                message.toolInvocations
                    .filterNot { isClarificationTool(it.toolName) }
                    .forEach { add(ThoughtStep.Tool(it)) }
            }
            if (steps.isNotEmpty()) {
                ChainOfThought(
                    steps = steps,
                    onApprove = onApproveTool,
                    onReject = onRejectTool
                )
                Spacer(Modifier.height(8.dp))
            }
            clarifications.forEach { inv ->
                InteractiveClarificationCard(invocation = inv, onApprove = onApproveTool)
                Spacer(Modifier.height(8.dp))
            }
            if (message.content.isNotEmpty()) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(
                            topStart = 4.dp,
                            topEnd = 18.dp,
                            bottomStart = 18.dp,
                            bottomEnd = 18.dp
                        ))
                        .background(Color(0xFF1A1A1A))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (message.content.isNotEmpty()) menuOpen = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        Column {
                            MarkdownText(
                                text = message.content,
                                color = Color(0xFFECECEC),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            menuOpen = false
                        }
                    )
                    if (isLastAssistant) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onStartEdit()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.continue_generating)) },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = {
                                menuOpen = false
                                onContinue()
                            }
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.regenerate_from_here)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = {
                            menuOpen = false
                            onRegenerateFrom()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
            } else if (isLastAssistant && isStreaming) {
                TypingDots()
            }
        }
        // 只有最后一条助手的消息会显示继续和编辑按钮
        if (message.content.isNotEmpty() && !editing && !isStreaming) {
            Spacer(Modifier.height(8.dp))
            AssistantActionBar(
                onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                onContinue = if (isLastAssistant) onContinue else null,
                onEdit = if (isLastAssistant) onStartEdit else null
            )
        }
    }
}

@Composable
internal fun AssistantActionBar(
    onCopy: () -> Unit,
    onContinue: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(Icons.Default.ContentCopy, stringResource(R.string.copy), onCopy)
        if (onContinue != null) {
            ActionChip(Icons.Default.PlayArrow, stringResource(R.string.continue_generating), onContinue)
        }
        if (onEdit != null) {
            ActionChip(Icons.Default.Edit, stringResource(R.string.edit), onEdit)
        }
    }
}

@Composable
internal fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp),
            tint = Color(0xFFECECEC))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = Color(0xFFECECEC))
    }
}

@Composable
internal fun TypingDots() {
    val infinite = rememberInfiniteTransition(label = "typing")
    val alpha by infinite.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (i == 0) alpha
                            else if (i == 1) (1f - alpha)
                            else alpha * 0.7f + 0.3f
                        )
                    )
            )
        }
    }
}

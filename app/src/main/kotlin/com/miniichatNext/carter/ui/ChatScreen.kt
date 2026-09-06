package com.miniichatNext.carter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.AppSettings
import com.miniichatNext.carter.data.Conversation
import com.miniichatNext.carter.data.Message
import com.miniichatNext.carter.data.ProviderConfig

@Composable
fun ChatScreen(
    conversation: Conversation?,
    settings: AppSettings,
    activeProvider: ProviderConfig?,
    isStreaming: Boolean,
    streamingOverlay: Pair<String, String>? = null,
    assistant: com.miniichatNext.carter.data.Assistant? = null,
    assistants: List<com.miniichatNext.carter.data.Assistant> = emptyList(),
    skills: List<com.miniichatNext.carter.data.Skill> = emptyList(),
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onContextCompress: () -> Unit = {},
    onSuggestions: (Int) -> Unit = {},
    onRenameChat: (String) -> Unit = {},
    effectiveSkillIds: Set<String> = emptySet(),
    onSelectAssistant: (String) -> Unit = {},
    onMenu: () -> Unit,
    onSend: (String, List<com.miniichatNext.carter.data.Attachment>) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateFrom: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onContinue: () -> Unit = {},
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<com.miniichatNext.carter.data.Attachment>>(emptyList()) }
    val listState = rememberLazyListState()
    val rawMessages = conversation?.messages ?: emptyList()
    val messages = remember(rawMessages, streamingOverlay) {
        val ov = streamingOverlay
        if (ov == null) rawMessages
        else rawMessages.map { if (it.id == ov.first) it.copy(content = ov.second) else it }
    }
    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDraft by rememberSaveable { mutableStateOf("") }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var showSuggestionPanel by rememberSaveable { mutableStateOf(false) }
    var suggestionPage by rememberSaveable { mutableStateOf(0) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val immersiveAlpha by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        animationSpec = tween(durationMillis = 320),
        label = "immersive-alpha"
    )

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val backgroundPath = assistant?.backgroundPath
    val backgroundOpacity = assistant?.backgroundOpacity ?: 1f
    val backgroundBitmap = rememberBackgroundBitmap(backgroundPath)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { immersive = true },
                    onTap = { immersive = false }
                )
            }
    ) {
        if (backgroundBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = backgroundBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f - backgroundOpacity))
            )
        }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = if (backgroundBitmap != null) 0.0f else 1f))
            .imePadding()
            .graphicsLayer { alpha = immersiveAlpha }
    ) {
        ChatTopBar(
            title = conversation?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.app_name),
            onMenu = onMenu,
            onEditTitle = {
                renameDraft = conversation?.title ?: ""
                showRenameDialog = true
            },
            onNew = onNew
        )

        if (messages.isEmpty()) {
            EmptyState(
                onPick = { onSend(it, emptyList()) },
                onOpenSettings = onOpenSettings,
                showSettingsHint = activeProvider == null,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageItem(
                        message = msg,
                        senderLabel = if (msg.role == "user") stringResource(R.string.profile_default_name)
                        else settings.activeModel.ifBlank { activeProvider?.name ?: stringResource(R.string.assistant) },
                        isLastAssistant = msg.id == messages.lastOrNull()?.id && msg.role == "assistant",
                        isStreaming = isStreaming,
                        editing = editingMessageId == msg.id,
                        editingDraft = if (editingMessageId == msg.id) editingDraft else "",
                        onEditingDraftChange = { editingDraft = it },
                        onStartEdit = {
                            editingMessageId = msg.id
                            editingDraft = msg.content
                        },
                        onCommitEdit = {
                            editingMessageId?.let { id ->
                                onEditMessage(id, editingDraft)
                            }
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onCancelEdit = {
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onContinue = onContinue,
                        onDelete = { onDeleteMessage(msg.id) },
                        onRegenerateFrom = { onRegenerateFrom(msg.id) }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = messages.isNotEmpty()
                && messages.last().role == "assistant"
                && !isStreaming
                && messages.last().content.isNotEmpty(),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .clickable(onClick = onRegenerate)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.regenerate),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .clickable {
                            suggestionPage = 0
                            showSuggestionPanel = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.reply_candidates),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            attachments = pendingAttachments,
            onAttachmentsChange = { pendingAttachments = it },
            modelLabel = settings.activeModel.ifBlank { stringResource(R.string.select_model) },
            onPickModel = onPickModel,
            skills = skills,
            enabledSkillIds = effectiveSkillIds,
            onToggleSkill = onToggleSkill,
            onContextCompress = onContextCompress,
            onSend = {
                val text = input
                val atts = pendingAttachments
                input = ""
                pendingAttachments = emptyList()
                onSend(text, atts)
            },
            onStop = onStop,
            isStreaming = isStreaming,
            enabled = activeProvider != null && settings.activeModel.isNotBlank()
        )
    }

    // Immersive: double-tap empty area to hide UI and show only the background.
    if (immersive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures { immersive = false }
                }
        )
    }
    }

    // Rename dialog
    if (showRenameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onRenameChat(renameDraft.ifBlank { "" })
                    showRenameDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Suggestion panel (4 candidates per page, 12 pages max, editable).
        if (showSuggestionPanel) {
            SuggestionPanel(
                conversation = conversation,
                page = suggestionPage,
                onPageChange = { suggestionPage = it },
                onGenerate = { onSuggestions(it) },
                onPick = { candidate ->
                    showSuggestionPanel = false
                    onSend(candidate, emptyList())
                },
                onDismiss = { showSuggestionPanel = false }
            )
        }

    
}

@Composable
private fun rememberBackgroundBitmap(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (path.isNullOrBlank()) return null
    if (!java.io.File(path).exists()) return null
    return androidx.compose.runtime.remember(path) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    onMenu: () -> Unit,
    onEditTitle: () -> Unit,
    onNew: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = "menu",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 4.dp)
                    .clickable(onClick = onEditTitle),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onNew) {
                Icon(Icons.Default.Edit, contentDescription = "new chat",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun MessageItem(
    message: Message,
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
    onDelete: () -> Unit = {},
    onRegenerateFrom: () -> Unit = {}
) {
    val isUser = message.role == "user"
    if (isUser) {
        UserBubble(
            message = message,
            editing = editing,
            editingDraft = editingDraft,
            onEditingDraftChange = onEditingDraftChange,
            onStartEdit = onStartEdit,
            onCommitEdit = onCommitEdit,
            onCancelEdit = onCancelEdit,
            onDelete = onDelete,
            onRegenerateFrom = onRegenerateFrom
        )
    } else {
        AssistantRow(
            message = message,
            senderLabel = senderLabel,
            isLastAssistant = isLastAssistant,
            isStreaming = isStreaming,
            editing = editing,
            editingDraft = editingDraft,
            onEditingDraftChange = onEditingDraftChange,
            onStartEdit = onStartEdit,
            onCommitEdit = onCommitEdit,
            onCancelEdit = onCancelEdit,
            onContinue = onContinue,
            onDelete = onDelete
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: Message,
    editing: Boolean,
    editingDraft: String,
    onEditingDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCommitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = Alignment.End
        ) {
            if (message.attachments.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    message.attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (att.type == "image")
                                    Icons.Default.Image
                                else
                                    Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                att.name,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                maxLines = 1
                            )
                        }
                    }
                }
                if (message.content.isNotEmpty() || editing) Spacer(Modifier.height(4.dp))
            }

            if (editing) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = editingDraft,
                        onValueChange = onEditingDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
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
            } else if (message.content.isNotEmpty()) {
                Box {
                    Box(
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = 18.dp,
                                    bottomEnd = 4.dp
                                )
                            )
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyLarge
                            )
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
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onStartEdit()
                            }
                        )
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
                                    Icons.Default.Delete,
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
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AssistantRow(
    message: Message,
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
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarChip(isUser = false)
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
            // Inline edit for the assistant message (last one only).
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
        } else if (message.content.isEmpty() && isLastAssistant && isStreaming) {
            TypingDots()
        } else {
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
                        MarkdownText(
                            text = message.content,
                            color = Color(0xFFECECEC),
                            modifier = Modifier.fillMaxWidth()
                        )
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
        }
        // Only the very last assistant message gets the "continue" / "edit" chips.
        // Earlier assistant messages just expose copy.
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
private fun AssistantActionBar(
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
private fun ActionChip(
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
private fun AvatarChip(isUser: Boolean) {
    val bg = if (isUser) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (isUser) "U" else "M",
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CopyButton(content: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1200); copied = false } }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboard.setText(AnnotatedString(content))
                copied = true
            }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "copy",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (copied) stringResource(R.string.copy) + " ✓" else stringResource(R.string.copy),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TypingDots() {
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

@Composable
private fun EmptyState(
    onPick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    showSettingsHint: Boolean,
    modifier: Modifier = Modifier
) {
    val examples = listOf(
        stringResource(R.string.example_prompt_1),
        stringResource(R.string.example_prompt_2),
        stringResource(R.string.example_prompt_3),
        stringResource(R.string.example_prompt_4)
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("M", color = MaterialTheme.colorScheme.onSurface,
                fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showSettingsHint) {
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Settings, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.error_no_provider),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { example ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable { onPick(example) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(example, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionPanel(
    conversation: Conversation?,
    page: Int,
    onPageChange: (Int) -> Unit,
    onGenerate: (Int) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val current = conversation
    val stored = current?.suggestionPages?.get(page)
        ?: if ((current?.suggestionSeed ?: -1) == page) current?.chatSuggestions.orEmpty() else emptyList()
    val showStored = stored.isNotEmpty()

    // Editable drafts, initialised from stored suggestions when the page matches.
    var drafts by remember(stored, page, showStored) {
        mutableStateOf(
            if (showStored) stored else List(4) { "" }
        )
    }

    // Auto-generate on first entering the page (only this page's 4 candidates).
    LaunchedEffect(page, showStored) {
        if (!showStored) onGenerate(page)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reply_candidates),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${page + 1} / 12",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    OutlinedFieldBox {
                        androidx.compose.foundation.text.BasicTextField(
                            value = drafts.getOrElse(i) { "" },
                            onValueChange = { v ->
                                drafts = drafts.toMutableList().also { if (i < it.size) it[i] = v }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            minLines = 1,
                            maxLines = 2
                        )
                    }
                }
                if (!showStored) {
                    Text(
                        stringResource(R.string.suggestion_generate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = {
                    if (page > 0) onPageChange(page - 1)
                }) { Text("‹") }
                androidx.compose.material3.TextButton(onClick = {
                    if (page < 11) onPageChange(page + 1)
                }) { Text("›") }
                androidx.compose.material3.TextButton(onClick = { onGenerate(page) }) {
                    Text(stringResource(R.string.regenerate))
                }
            }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                androidx.compose.material3.TextButton(onClick = {
                    val candidate = drafts.firstOrNull { it.isNotBlank() }
                    if (candidate != null) onPick(candidate) else onDismiss()
                }) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    )
}

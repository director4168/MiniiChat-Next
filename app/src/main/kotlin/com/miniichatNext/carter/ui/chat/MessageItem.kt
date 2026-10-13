package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.ui.components.AvatarView
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.util.AttachmentLoader
import com.miniichatNext.carter.ui.markdown.MarkdownText

@Composable
internal fun MessageItem(
    message: Message,
    avatar: com.miniichatNext.carter.data.avatar.Avatar,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    onStartEdit: () -> Unit = {},
    onContinue: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerateFrom: () -> Unit = {},
    onApproveTool: ((com.miniichatNext.carter.data.model.ToolInvocation, String?) -> Unit)? = null,
    onRejectTool: ((com.miniichatNext.carter.data.model.ToolInvocation) -> Unit)? = null,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    val isUser = message.role == "user"
    // 长按→底部操作折叠框（ModalBottomSheet；锚定DropdownMenu在列表项里会触发布局滚动跳变）
    var showActionsSheet by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // 工具结果已内嵌在assistant消息的toolInvocations中，独立的tool角色消息不渲染
    if (message.role == "tool") return
    if (isUser) {
        UserBubble(
            message = message,
            avatar = avatar,
            onStartEdit = onStartEdit,
            onDelete = onDelete,
            onRegenerateFrom = onRegenerateFrom,
            onFullscreen = onFullscreen,
            onLongPress = { showActionsSheet = true },
        )
    } else {
        AssistantRow(
            message = message,
            avatar = avatar,
            senderLabel = senderLabel,
            isLastAssistant = isLastAssistant,
            isStreaming = isStreaming,
            onStartEdit = onStartEdit,
            onContinue = onContinue,
            onDelete = onDelete,
            onRegenerateFrom = onRegenerateFrom,
            onApproveTool = onApproveTool,
            onRejectTool = onRejectTool,
            onFullscreen = onFullscreen,
            onLongPress = { showActionsSheet = true },
        )
    }
    if (showActionsSheet) {
        MessageActionsSheet(
            isLastAssistant = isLastAssistant && message.role == "assistant",
            onCopy = {
                clipboard.setText(AnnotatedString(message.content))
                showActionsSheet = false
            },
            onEdit = { showActionsSheet = false; onStartEdit() },
            onRetell = { showActionsSheet = false; onRegenerateFrom() },
            onContinue = { showActionsSheet = false; onContinue() },
            onDelete = { showActionsSheet = false; onDelete() },
            onDismiss = { showActionsSheet = false },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(
    message: Message,
    avatar: com.miniichatNext.carter.data.avatar.Avatar,
    onStartEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
    onLongPress: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = Alignment.End
        ) {
            if (message.content.isNotEmpty() || message.attachments.isNotEmpty()) {
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
                                onLongClick = onLongPress
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column {
                            if (message.attachments.isNotEmpty()) {
                                UserAttachmentsRow(
                                    attachments = message.attachments,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                                if (message.content.isNotBlank()) Spacer(Modifier.height(8.dp))
                            }
                            if (message.content.isNotEmpty()) {
                                SelectionContainer {
                                    // 用户消息也走markdown渲染（与助手侧一致）
                                    com.miniichatNext.carter.ui.markdown.MarkdownText(
                                        text = message.content,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        onFullscreen = onFullscreen,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // 用户消息头像：渲染真实的用户资料头像
        com.miniichatNext.carter.ui.components.AvatarView(
            avatar = avatar,
            fallbackInitial = "U",
            size = 22.dp
        )
    }
}


/** 用户消息里的附件：图片横向排列（可左右滚动），文件显示名称+体积；点击用系统应用打开 */
@Composable
private fun UserAttachmentsRow(attachments: List<Attachment>, contentColor: Color) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    val images = attachments.filter { it.type == "image" }
    val files = attachments.filter { it.type != "image" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.forEach { att ->
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable { openAttachment(context, att) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(null, att.id, att.uri) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                decodeAttachmentThumbnail(context, att.uri, 320)
                            }
                        }
                        // 委托属性（by produceState）不能smart cast，先取局部变量
                        val thumb = bitmap
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = att.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(Icons.Default.Image, contentDescription = att.name, tint = contentColor)
                        }
                    }
                }
            }
        }
        if (files.isNotEmpty()) {
            // 列优先网格：每列4个，超出往右排并横向滚动
            androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val colWidth = maxWidth
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    files.chunked(4).forEach { columnFiles ->
                        Column(
                            modifier = Modifier.width(colWidth),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            columnFiles.forEach { att ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .clickable { openAttachment(context, att) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = contentColor,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            att.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = contentColor,
                                            maxLines = 1,
                                        )
                                        Text(
                                            AttachmentLoader.formatBytes(att.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = contentColor.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 用系统应用打开附件：优先原始content://URI（不依赖应用数据目录副本）；
 * 原始URI不可读时回退到应用内副本的FileProvider URI。
 */
private fun openAttachment(context: Context, att: Attachment) {
    val mime = att.mimeType.ifBlank { "*/*" }
    val candidates = buildList {
        if (att.originalUri.isNotBlank()) add(att.originalUri)
        add(att.uri)
    }
    for (raw in candidates) {
        val uri = runCatching {
            if (raw.startsWith("file://")) {
                androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", java.io.File(Uri.parse(raw).path!!)
                )
            } else {
                Uri.parse(raw)
            }
        }.getOrNull() ?: continue
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    Toast.makeText(context, R.string.markdown_open_link_failed, Toast.LENGTH_SHORT).show()
}

/** 采样解码content://URI缩略图 */
private fun decodeAttachmentThumbnail(context: Context, uriString: String, targetPx: Int): ImageBitmap? =
    runCatching {
        val uri = Uri.parse(uriString)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)
            ?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)
            ?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()?.asImageBitmap()

/** 消息长按操作折叠框：ModalBottomSheet（锚定DropdownMenu在LazyColumn项里会引发滚动跳变，弃用） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    isLastAssistant: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRetell: () -> Unit,
    onContinue: () -> Unit = {},
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        MessageSheetActionRow(
            icon = Icons.Default.ContentCopy,
            label = stringResource(R.string.copy),
            onClick = onCopy
        )
        MessageSheetActionRow(
            icon = Icons.Default.Edit,
            label = stringResource(R.string.edit),
            onClick = onEdit
        )
        MessageSheetActionRow(
            icon = Icons.Default.Refresh,
            label = stringResource(R.string.regenerate_from_here),
            onClick = onRetell
        )
        if (isLastAssistant) {
            MessageSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = stringResource(R.string.continue_generating),
                onClick = onContinue
            )
        }
        MessageSheetActionRow(
            icon = Icons.Default.Delete,
            label = stringResource(R.string.delete),
            onClick = onDelete,
            danger = true
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MessageSheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
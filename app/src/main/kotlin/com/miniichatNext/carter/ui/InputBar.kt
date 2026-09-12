package com.miniichatNext.carter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.Attachment
import com.miniichatNext.carter.data.Skills.Skill
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
    // CSS/图片背景模式下让输入栏容器透明（输入框本体仍保留 surfaceVariant 底色）
    transparent: Boolean = false
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var skillSheetOpen by remember { mutableStateOf(false) }

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

        // Main row: [● model][input][+][↑]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Model sphere
            Box(
                modifier = Modifier
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

            // Input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 160.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(
                        color = LocalContentColor.current,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 6,
                    enabled = enabled
                )
            }
            Spacer(Modifier.width(6.dp))

            // Plus → menu
            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            keyboard?.hide()
                            focus.clearFocus()
                            menuOpen = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.attach),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_image)) },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = {
                            menuOpen = false
                            pickImage.launch("image/*")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_file)) },
                        leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                        onClick = {
                            menuOpen = false
                            pickFile.launch("*/*")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.context_compress)) },
                        leadingIcon = { Icon(Icons.Default.Compress, null) },
                        onClick = {
                            menuOpen = false
                            onContextCompress()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.assistant_skills)) },
                        leadingIcon = { Icon(Icons.Default.Extension, null) },
                        onClick = {
                            menuOpen = false
                            skillSheetOpen = true
                        }
                    )
                }
            }
            Spacer(Modifier.width(6.dp))

            // Send / stop
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
                    contentDescription = if (isStreaming) "stop" else "send",
                    tint = sendFg,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Secondary function row appears when the IME is visible and hides with it,
        // mirroring RikkaHub's ChatInput two-layer behaviour.
        val imeVisible = WindowInsets.isImeVisible
        if (imeVisible && enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FuncChip(Icons.Default.Image, stringResource(R.string.attach_image)) {
                    pickImage.launch("image/*")
                }
                FuncChip(Icons.Default.AttachFile, stringResource(R.string.attach_file)) {
                    pickFile.launch("*/*")
                }
                FuncChip(Icons.Default.Compress, stringResource(R.string.context_compress)) {
                    onContextCompress()
                }
                FuncChip(Icons.Default.Extension, stringResource(R.string.assistant_skills)) {
                    skillSheetOpen = true
                }
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

@Composable
private fun FuncChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (att.type == "image") Icons.Default.Image
            else Icons.Default.AttachFile,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                att.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1
            )
            Text(
                AttachmentLoader.formatBytes(att.sizeBytes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "remove",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

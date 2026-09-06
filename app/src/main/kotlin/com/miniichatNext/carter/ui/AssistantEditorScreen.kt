package com.miniichatNext.carter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.Assistant
import com.miniichatNext.carter.data.Avatar
import com.miniichatNext.carter.data.Skill
import com.miniichatNext.carter.ui.components.AvatarPicker
import com.miniichatNext.carter.ui.components.AvatarView
import com.miniichatNext.carter.util.AvatarStorage
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.launch

@Composable
fun AssistantEditorScreen(
    initial: Assistant?,
    availableSkills: List<Skill>,
    onCancel: () -> Unit,
    onSave: (Assistant) -> Unit,
    onDelete: () -> Unit = {},
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var avatar by remember(initial?.id) { mutableStateOf(initial?.avatar ?: "🤖") }
    var avatarPath by remember(initial?.id) { mutableStateOf(initial?.avatarPath) }
    var systemPrompt by remember(initial?.id) { mutableStateOf(initial?.systemPrompt ?: "") }
    var hasTemp by remember(initial?.id) { mutableStateOf(initial?.temperature != null) }
    var temp by remember(initial?.id) { mutableStateOf(initial?.temperature ?: 0.7f) }
    var backgroundPath by remember(initial?.id) { mutableStateOf(initial?.backgroundPath) }
    var backgroundOpacity by remember(initial?.id) { mutableStateOf(initial?.backgroundOpacity ?: 1f) }
    var enabledSkills by remember(initial?.id) {
        mutableStateOf(initial?.enabledSkillIds?.toSet() ?: emptySet())
    }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val bgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val path = AvatarStorage.saveFromUri(context, newId(), uri, maxSide = 4096)
            backgroundPath = path
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                if (initial == null) stringResource(R.string.add_assistant)
                else stringResource(R.string.edit_assistant),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (initial != null) {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val validSkillIds = enabledSkills.intersect(
                        availableSkills.map { it.id }.toSet()
                    )
                    val a = (initial ?: Assistant(id = newId(), name = name.trim()))
                        .copy(
                            name = name.trim(),
                            avatar = avatar.trim().ifBlank { "🤖" },
                            avatarPath = avatarPath,
                            systemPrompt = systemPrompt,
                            temperature = if (hasTemp) temp else null,
                            backgroundPath = backgroundPath,
                            backgroundOpacity = backgroundOpacity,
                            enabledSkillIds = validSkillIds.toList()
                        )
                    onSave(a)
                    val previous = initial?.enabledSkillIds?.toSet() ?: emptySet()
                    (validSkillIds - previous).forEach { onToggleSkill(it, true) }
                    (previous - validSkillIds).forEach { onToggleSkill(it, false) }
                }
            ) { Text(stringResource(R.string.action_save)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showAvatarPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    AvatarView(
                        avatar = avatar,
                        avatarPath = avatarPath,
                        fallbackInitial = name.take(1).ifBlank { "?" },
                        size = 56.dp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.assistant_name),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    FieldBox {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = LocalContentColor.current, fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.assistant_avatar))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    FieldBox {
                        BasicTextField(
                            value = avatar,
                            onValueChange = {
                                avatar = it
                                if (it.isNotBlank()) avatarPath = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = LocalContentColor.current, fontSize = 18.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showAvatarPicker = true }) {
                    Text(stringResource(R.string.skill_pick_or_crop))
                }
            }

            SectionLabel(stringResource(R.string.assistant_system_prompt))
            FieldBox {
                BasicTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = LocalContentColor.current, fontSize = 13.sp, lineHeight = 18.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 3,
                    maxLines = 7
                )
            }
            Text(
                stringResource(R.string.prompt_vars_hint),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hasTemp, onCheckedChange = { hasTemp = it })
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.override_temperature),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium
                )
                if (hasTemp) {
                    Text("%.2f".format(temp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (hasTemp) {
                Slider(
                    value = temp,
                    onValueChange = { temp = it },
                    valueRange = 0f..2f,
                    steps = 19
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            SectionLabel(stringResource(R.string.assistant_background))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val currentBgPath = backgroundPath
                    if (currentBgPath != null && java.io.File(currentBgPath).exists()) {
                        val bitmap = remember(currentBgPath) {
                            runCatching {
                                android.graphics.BitmapFactory.decodeFile(currentBgPath)
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            bgPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) { Text(stringResource(R.string.background_pick_image)) }
                        if (backgroundPath != null) {
                            TextButton(onClick = {
                                backgroundPath?.let { AvatarStorage.delete(context, it) }
                                backgroundPath = null
                            }) { Text(stringResource(R.string.background_clear)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${stringResource(R.string.background_opacity)}: " + stringResource(
                    R.string.background_opacity_value,
                    (backgroundOpacity * 100).toInt()
                ),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = backgroundOpacity,
                onValueChange = { backgroundOpacity = it },
                valueRange = 0f..1f,
                steps = 19
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            SectionLabel(stringResource(R.string.assistant_skills))
            Text(
                stringResource(R.string.assistant_skills_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (availableSkills.isEmpty()) {
                Text(
                    stringResource(R.string.skill_no_skills_for_assistant),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                availableSkills.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            if (s.description.isNotBlank()) {
                                Text(
                                    s.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        Switch(
                            checked = s.id in enabledSkills,
                            onCheckedChange = { en ->
                                enabledSkills = if (en) enabledSkills + s.id else enabledSkills - s.id
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showAvatarPicker) {
        AvatarPicker(
            current = Avatar.fromLegacy(avatar, avatarPath),
            onChange = { picked ->
                when (picked) {
                    is Avatar.Image -> { avatar = ""; avatarPath = picked.path }
                    is Avatar.Emoji -> { avatar = picked.content; avatarPath = null }
                    Avatar.None -> { avatar = "🤖"; avatarPath = null }
                }
                showAvatarPicker = false
            },
            onDismiss = { showAvatarPicker = false }
        )
    }

    if (showDeleteConfirm && initial != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete) + " · " + (initial.name)) },
            text = { Text(stringResource(R.string.assistant_delete_confirm, initial.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) { content() }
}
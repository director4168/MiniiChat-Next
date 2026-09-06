package com.miniichatNext.carter.ui.skills

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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.Skill
import com.miniichatNext.carter.data.SkillFrontmatterParser
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@Composable
fun SkillsScreen(
    skills: List<Skill>,
    onBack: () -> Unit,
    onSave: (Skill) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onImportError: (String) -> Unit = {},
    onImportSuccess: (String) -> Unit = {},
    onSaveFiles: (String, Map<String, ByteArray>) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Skill?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deletingTarget by remember { mutableStateOf<Skill?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException(context.getString(R.string.skill_import_failed_read))
                val text = bytes.toString(Charsets.UTF_8)
                val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                if (isZip) {
                    val files = linkedMapOf<String, ByteArray>()
                    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory) {
                                val path = entry.name.replace('\\', '/')
                                    .trimStart('/')
                                    .split('/')
                                    .filter { it.isNotBlank() && it != "." }
                                if (path.none { it == ".." } && path.isNotEmpty()) {
                                    files[path.joinToString("/")] = zip.readBytes()
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                    val skillPath = files.keys.firstOrNull {
                        it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)
                    } ?: throw IllegalStateException(context.getString(R.string.skill_import_failed_meta))
                    val skillText = files.getValue(skillPath).toString(Charsets.UTF_8)
                    val fallbackName = skillPath.substringBeforeLast('/', skillPath)
                        .substringAfterLast('/')
                    val parsed = SkillFrontmatterParser.parse(skillText, fallbackName)
                        ?: throw IllegalStateException(context.getString(R.string.skill_import_failed_empty))
                    if (parsed.name.isBlank() || parsed.description.isBlank()) {
                        throw IllegalStateException(context.getString(R.string.skill_import_failed_meta))
                    }
                    onSaveFiles(parsed.name, files.mapKeys { (path, _) ->
                        path.substringAfter("/", path)
                    })
                    onImportSuccess(parsed.name)
                } else {
                    val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".md")
                        ?: "skill_${System.currentTimeMillis()}"
                    val parsed = SkillFrontmatterParser.parse(text, fallbackName)
                        ?: throw IllegalStateException(context.getString(R.string.skill_import_failed_empty))
                    if (parsed.name.isBlank() || parsed.description.isBlank()) {
                        throw IllegalStateException(context.getString(R.string.skill_import_failed_meta))
                    }
                    val skill = Skill(
                        id = newId(),
                        name = parsed.name,
                        description = parsed.description,
                        body = parsed.body,
                        enabled = true
                    )
                    onSave(skill)
                    onImportSuccess(parsed.name)
                }
            } catch (e: Exception) {
                onImportError(context.getString(R.string.skill_import_failed, e.message ?: ""))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        TopBar(title = stringResource(R.string.skills), onBack = onBack)

        if (skills.isEmpty()) {
            EmptyState(
                onCreate = { creating = true },
                importLauncher = {
                    importLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionTile(
                        icon = Icons.Default.FileUpload,
                        label = stringResource(R.string.skills_import),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            importLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                        }
                    )
                    ActionTile(
                        icon = Icons.Default.Add,
                        label = stringResource(R.string.skills_new),
                        modifier = Modifier.weight(1f),
                        onClick = { creating = true }
                    )
                }
                Spacer(Modifier.height(4.dp))
                skills.forEach { s ->
                    SkillRow(
                        skill = s,
                        onToggle = { onSetEnabled(s.id, it) },
                        onEdit = { editing = s },
                        onDelete = { deletingTarget = s }
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (creating) {
        SkillEditor(
            initial = null,
            onCancel = { creating = false },
            onSave = { onSave(it); creating = false }
        )
    }
    editing?.let { target ->
        SkillEditor(
            initial = target,
            onCancel = { editing = null },
            onSave = { onSave(it); editing = null }
        )
    }

    deletingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingTarget = null },
            title = { Text(stringResource(R.string.delete) + " · " + target.name) },
            text = { Text(stringResource(R.string.skill_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deletingTarget = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun EmptyState(
    onCreate: () -> Unit,
    importLauncher: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.skills_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.skills_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    icon = Icons.Default.FileUpload,
                    label = stringResource(R.string.skills_import),
                    onClick = importLauncher
                )
                ActionTile(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.skills_new),
                    onClick = onCreate
                )
            }
        }
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SkillRow(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                skill.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                stringResource(R.string.skill_chars_count, skill.body.length),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
        }
    }
}

@Composable
private fun SkillEditor(
    initial: Skill?,
    onCancel: () -> Unit,
    onSave: (Skill) -> Unit
) {
    // Manual creation: user pastes a whole SKILL.md, we auto-parse name/description.
    var content by remember(initial?.id) {
        mutableStateOf(
            if (initial != null) {
                buildString {
                    append("---\nname: ").append(initial.name).append('\n')
                    if (initial.description.isNotBlank()) {
                        append("description: ").append(initial.description).append('\n')
                    }
                    append("---\n\n").append(initial.body)
                }
            } else ""
        )
    }

    val parsedName = remember(content) {
        val frontmatter = SkillFrontmatterParser.split(content).first
        Regex("""(?m)^\s*name:\s*(.+)\s*$""")
            .find(frontmatter)?.groupValues?.get(1)?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            .orEmpty()
    }
    val parsedDescription = remember(content) {
        val frontmatter = SkillFrontmatterParser.split(content).first
        Regex("""(?m)^\s*description:\s*(.+)\s*$""")
            .find(frontmatter)?.groupValues?.get(1)?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            .orEmpty()
    }
    val nameValid = parsedName.isNotBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (initial == null) stringResource(R.string.skill_new_title)
                else stringResource(R.string.skill_edit_title)
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 8,
                        maxLines = 16
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (nameValid) {
                    Text(
                        stringResource(R.string.skill_auto_name, parsedName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        stringResource(R.string.skill_name_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (parsedDescription.isNotBlank()) {
                    Text(
                        parsedDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameValid,
                onClick = {
                    val parsed = SkillFrontmatterParser.parse(content, parsedName) ?: return@TextButton
                    val skill = (initial ?: Skill(id = newId(), name = parsed.name))
                        .copy(
                            name = parsed.name,
                            description = parsed.description,
                            body = parsed.body
                        )
                    onSave(skill)
                }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    minLines: Int = 1,
    maxLines: Int = 1,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    color = LocalContentColor.current,
                    fontSize = 14.sp
                ),
                singleLine = minLines == 1 && maxLines == 1,
                minLines = minLines,
                maxLines = maxLines,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

package com.miniichatNext.carter.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.ui.components.OutlinedFieldBox
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SectionHeader
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.ui.components.AvatarView

@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderConfig>,
    assistants: List<Assistant>,
    skills: List<Skill>,
    userProfile: UserProfile,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenUserProfile: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenWorkspace: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    // 外部传入的滚动状态（AppRoot用rememberSaveable持有，跳转到关于再返回时保留位置）
    scrollState: ScrollState? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var system by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var temperature by rememberSaveable { mutableStateOf(settings.temperature) }
    val stream = settings.stream

    androidx.compose.runtime.LaunchedEffect(settings.systemPrompt) {
        if (settings.systemPrompt != system) system = settings.systemPrompt
    }
    androidx.compose.runtime.LaunchedEffect(settings.temperature) {
        if (settings.temperature != temperature) temperature = settings.temperature
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.settings), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 用AppRoot传入的ScrollState（如果提供），导航到关于页再返回时滚动位置保留
                .verticalScroll(scrollState ?: rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenUserProfile)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.miniichatNext.carter.ui.components.AvatarView(
                        avatar = userProfile.avatar,
                        fallbackInitial = userProfile.displayName.take(1),
                        size = 40.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile),
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            userProfile.displayName.ifBlank { stringResource(R.string.display_name_default) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProviders)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.providers),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.models_count_configured, providers.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAssistants)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.assistants),
                            style = MaterialTheme.typography.titleMedium)
                        val active = assistants.firstOrNull { it.id == settings.activeAssistantId }
                        Text(
                            active?.let {
                                val glyph = if (it.hasAvatarImage)
                                    it.name.take(1).ifBlank { "?" }
                                else it.avatar.ifBlank { it.name.take(1).ifBlank { "?" } }
                                "$glyph  ${it.name}"
                            } ?: "${assistants.size} ${stringResource(R.string.assistants)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionHeader(stringResource(R.string.section_behavior))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.setting_system),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedFieldBox {
                        BasicTextField(
                            value = system,
                            onValueChange = {
                                system = it
                                onChange { s -> s.copy(systemPrompt = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = LocalContentColor.current,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            minLines = 2,
                            maxLines = 5
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "${stringResource(R.string.setting_temperature)}: ${"%.2f".format(temperature)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = temperature,
                        onValueChange = {
                            temperature = it
                            onChange { s -> s.copy(temperature = it) }
                        },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_stream),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.server_sent_events),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = stream,
                            onCheckedChange = {
                                onChange { s -> s.copy(stream = it) }
                            }
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.section_aux_features))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    AuxModelRow(
                        label = stringResource(R.string.aux_model_title),
                        ref = settings.titleModel,
                        providers = providers,
                        onPick = { ref -> onChange { it.copy(titleModel = ref) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    AuxModelRow(
                        label = stringResource(R.string.aux_model_compress),
                        ref = settings.compressModel,
                        providers = providers,
                        onPick = { ref -> onChange { it.copy(compressModel = ref) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    AuxModelRow(
                        label = stringResource(R.string.aux_model_suggestion),
                        ref = settings.suggestionModel,
                        providers = providers,
                        onPick = { ref -> onChange { it.copy(suggestionModel = ref) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    AuxModelRow(
                        label = stringResource(R.string.aux_model_ocr),
                        ref = settings.ocrModel,
                        providers = providers,
                        onPick = { ref -> onChange { it.copy(ocrModel = ref) } }
                    )
                }
            }

            SectionHeader(stringResource(R.string.setting_appearance))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_dynamic_color),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.setting_dynamic_color_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { v ->
                                onChange { s -> s.copy(dynamicColor = v) }
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setting_theme_mode),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = listOf(
                            "system" to stringResource(R.string.setting_theme_system),
                            "light" to stringResource(R.string.setting_theme_light),
                            "dark" to stringResource(R.string.setting_theme_dark)
                        ),
                        selectedKey = settings.themeMode,
                        onSelect = { k -> onChange { s -> s.copy(themeMode = k) } }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setting_language),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = listOf(
                            "system" to stringResource(R.string.setting_language_system),
                            "en" to stringResource(R.string.setting_language_en),
                            "zh" to stringResource(R.string.setting_language_zh)
                        ),
                        selectedKey = settings.language,
                        onSelect = { k -> onChange { s -> s.copy(language = k) } }
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_advanced))
            SectionCard {
                // SectionCard是Box：多子元素必须套Column
                Column {
                    SettingsNavRow(
                        icon = Icons.Default.Storage,
                        title = stringResource(R.string.workspace_title),
                        subtitle = stringResource(R.string.workspace_hint),
                        onClick = onOpenWorkspace,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsNavRow(
                        icon = Icons.Default.Hub,
                        title = stringResource(R.string.mcp_title),
                        subtitle = stringResource(R.string.mcp_hint),
                        onClick = onOpenMcp,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsNavRow(
                        icon = Icons.Default.Extension,
                        title = stringResource(R.string.skills),
                        subtitle = if (skills.isEmpty()) stringResource(R.string.skills_subtitle_empty)
                        else stringResource(R.string.skills_subtitle_count, skills.count { it.enabled }),
                        onClick = onOpenSkills,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    // 用系统文件管理器直接打开应用私有目录（走本应用的DocumentsProvider）
                    SettingsNavRow(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(R.string.setting_open_private_dir),
                        subtitle = stringResource(R.string.setting_open_private_dir_hint),
                        onClick = {
                            val ok = com.miniichatNext.carter.AppDataDocumentsProvider.openRoot(context)
                            if (!ok) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.setting_open_private_dir_failed),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_about))
            // 关于入口：从内联展开改成独立页面跳转，关于文本 / 链接 / 版本号
            // 都在AboutScreen里展示，这里只保留一个导航行
            SectionCard {
                // 注意：SectionCard内部是Box（不是Column），多个子元素会互相叠画→必须自己套Column
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAbout)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.section_about),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.about_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.token_usage))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    TokenStatRow(stringResource(R.string.token_usage_input), settings.totalPromptTokens)
                    Spacer(Modifier.height(8.dp))
                    TokenStatRow(stringResource(R.string.token_usage_output), settings.totalCompletionTokens)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                    TokenStatRow(
                        stringResource(R.string.token_usage_total),
                        settings.totalPromptTokens + settings.totalCompletionTokens,
                        emphasize = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.token_usage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TokenStatRow(
    label: String,
    value: Int,
    emphasize: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "%,d".format(value),
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SegmentedRow(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        options.forEach { (key, label) ->
            val selected = key == selectedKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AuxModelRow(
    label: String,
    ref: String,
    providers: List<ProviderConfig>,
    onPick: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val activeProviderId = remember(ref) {
        if (ref.contains("::")) ref.substringBefore("::") else ""
    }
    val activeModelId = remember(ref) {
        if (ref.contains("::")) ref.substringAfter("::") else ref
    }
    val displayName = remember(ref, providers) {
        val provider = providers.firstOrNull { it.id == activeProviderId }
        val model = activeModelId.ifBlank { "" }
        if (provider != null && model.isNotBlank()) {
            "${provider.name} / $model"
        } else if (model.isNotBlank()) {
            model
        } else {
            "" // not set
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge)
        Text(
            displayName.ifBlank { stringResource(R.string.aux_model_not_set) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (displayName.isBlank())
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
    if (showPicker) {
        AuxModelPickerDialog(
            providers = providers,
            selected = ref,
            onPick = {
                showPicker = false
                onPick(it)
            },
            onClear = {
                showPicker = false
                onPick("")
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun AuxModelPickerDialog(
    providers: List<ProviderConfig>,
    selected: String,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_model)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (providers.isEmpty()) {
                    Text(stringResource(R.string.no_providers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    providers.forEach { p ->
                        val pids = p.modelIds()
                        if (pids.isEmpty()) {
                            Text(
                                "${p.name} — (no models)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        } else {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            pids.forEach { m ->
                                val key = "${p.id}::$m"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(key) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = key == selected,
                                        onClick = { onPick(key) }
                                    )
                                    Text(m, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onClear) {
                    Text(stringResource(R.string.clear))
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

/** 设置页的一行导航项（图标 + 标题 + 副标题 + 箭头）。 */
@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

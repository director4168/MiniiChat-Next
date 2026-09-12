package com.miniichatNext.carter.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.ProviderConfig
import com.miniichatNext.carter.data.ProviderPresets
import com.miniichatNext.carter.util.newId

@Composable
fun ProviderEditorScreen(
    initial: ProviderConfig?,
    onCancel: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember(initial?.id) { mutableStateOf(initial?.apiKey ?: "") }
    var chatCompletionsPath by remember(initial?.id) {
        mutableStateOf(initial?.chatCompletionsPath ?: "/chat/completions")
    }
    var useResponseApi by remember(initial?.id) { mutableStateOf(initial?.useResponseApi ?: false) }
    var presetBaseUrl by remember(initial?.id) { mutableStateOf("") }
    var providerType by remember(initial?.id) {
        mutableStateOf(initial?.type() ?: com.miniichatNext.carter.data.ProviderType.OPENAI)
    }
    var thinkingLevel by remember(initial?.id) {
        mutableStateOf(initial?.thinking() ?: com.miniichatNext.carter.data.ThinkingLevel.OFF)
    }
    var maxTokens by remember(initial?.id) { mutableStateOf((initial?.maxTokens ?: 8192).toString()) }
    var headerRows by remember(initial?.id) {
        mutableStateOf<List<Pair<String, String>>>(
            initial?.customHeaders?.map { it.key to it.value } ?: emptyList()
        )
    }
    var bodyRows by remember(initial?.id) {
        mutableStateOf<List<Pair<String, String>>>(
            initial?.extraBody?.map { it.key to it.value } ?: emptyList()
        )
    }
    var advancedOpen by remember(initial?.id) {
        mutableStateOf((initial?.customHeaders?.isNotEmpty() == true)
            || (initial?.extraBody?.isNotEmpty() == true))
    }
    var presetSheetOpen by remember(initial?.id) { mutableStateOf(initial == null) }
    var showResponseApiWarning by remember { mutableStateOf(false) }

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
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Text(
                if (initial == null) stringResource(R.string.add_provider)
                else stringResource(R.string.edit_provider),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = name.isNotBlank()
                    && (baseUrl.isNotBlank() || presetBaseUrl.isNotBlank() || initial?.baseUrl?.isNotBlank() == true),
                onClick = {
                    val effective = baseUrl.trim().ifEmpty {
                        presetBaseUrl.ifEmpty { initial?.baseUrl ?: "" }
                    }
                    // Keep the base URL exactly as the user typed it — do not auto-append paths.
                    val normalizedUrl = effective.trimEnd('/')
                    val headers = headerRows.filter { it.first.isNotBlank() }
                        .associate { it.first.trim() to it.second.trim() }
                    val body = bodyRows.filter { it.first.isNotBlank() }
                        .associate { it.first.trim() to it.second.trim() }
                    val p = (initial ?: ProviderConfig(
                        id = newId(),
                        name = name.trim(),
                        baseUrl = normalizedUrl,
                        apiKey = apiKey.trim()
                        // 新建 provider 不预填模型，由 ChatViewModel.fetchModels
                        // 按钮点击后自动拉取（或用户用"手动添加"补）
                    )).copy(
                        name = name.trim(),
                        baseUrl = normalizedUrl,
                        apiKey = apiKey.trim(),
                        customHeaders = headers,
                        extraBody = body,
                        providerType = providerType.name,
                        thinkingLevel = thinkingLevel.name,
                        maxTokens = maxTokens.toIntOrNull() ?: 8192,
                        chatCompletionsPath = chatCompletionsPath.trim().ifBlank { "/chat/completions" },
                        useResponseApi = useResponseApi
                    )
                    onSave(p)
                }
            ) { Text(stringResource(R.string.action_save)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (initial == null) {
                TextButton(onClick = { presetSheetOpen = true }) {
                    Text(stringResource(R.string.choose_preset))
                }
            }

            FieldSection(stringResource(R.string.provider_type)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp)
                ) {
                    com.miniichatNext.carter.data.ProviderType.entries.forEach { type ->
                        val selected = providerType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.surface
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { providerType = type }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (type) {
                                    com.miniichatNext.carter.data.ProviderType.OPENAI -> stringResource(R.string.provider_type_openai)
                                    com.miniichatNext.carter.data.ProviderType.CLAUDE -> stringResource(R.string.provider_type_claude)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (providerType == com.miniichatNext.carter.data.ProviderType.CLAUDE) {
                FieldSection(stringResource(R.string.thinking_level)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(3.dp)
                    ) {
                        com.miniichatNext.carter.data.ThinkingLevel.entries.forEach { lvl ->
                            val selected = thinkingLevel == lvl
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surface
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable { thinkingLevel = lvl }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when (lvl) {
                                        com.miniichatNext.carter.data.ThinkingLevel.OFF -> stringResource(R.string.thinking_off)
                                        com.miniichatNext.carter.data.ThinkingLevel.LOW -> stringResource(R.string.thinking_low)
                                        com.miniichatNext.carter.data.ThinkingLevel.MEDIUM -> stringResource(R.string.thinking_medium)
                                        com.miniichatNext.carter.data.ThinkingLevel.HIGH -> stringResource(R.string.thinking_high)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (thinkingLevel != com.miniichatNext.carter.data.ThinkingLevel.OFF) {
                        Text(
                            stringResource(R.string.thinking_budget_label, thinkingLevel.budgetTokens),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }
                }

                FieldSection(stringResource(R.string.max_tokens)) {
                    EditableValue(
                        value = maxTokens,
                        placeholder = "8192",
                        monospace = true
                    ) { maxTokens = it }
                }
            }

            FieldSection(stringResource(R.string.provider_name)) {
                EditableValue(name, "") { name = it }
            }
            FieldSection(stringResource(R.string.setting_base_url)) {
                EditableValue(
                    value = baseUrl,
                    placeholder = presetBaseUrl.ifEmpty { initial?.baseUrl ?: "https://…" },
                    monospace = true
                ) { baseUrl = it }
            }
            FieldSection(stringResource(R.string.setting_api_key)) {
                EditableValue(apiKey, "", monospace = true) { apiKey = it }
            }

            if (providerType == com.miniichatNext.carter.data.ProviderType.OPENAI) {
                FieldSection(stringResource(R.string.chat_completions_path)) {
                    EditableValue(
                        value = chatCompletionsPath,
                        placeholder = "/chat/completions",
                        monospace = true
                    ) { chatCompletionsPath = it }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.response_api),
                            style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.response_api_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useResponseApi,
                        onCheckedChange = { v ->
                            if (v && !useResponseApi) {
                                showResponseApiWarning = true
                            } else {
                                useResponseApi = v
                            }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { advancedOpen = !advancedOpen }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.advanced_options),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (advancedOpen) Icons.Default.ExpandLess
                    else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (advancedOpen) {
                KeyValueEditor(
                    title = stringResource(R.string.custom_headers),
                    hint = stringResource(R.string.custom_headers_hint),
                    rows = headerRows,
                    onChange = { headerRows = it.toMutableList() }
                )
                KeyValueEditor(
                    title = stringResource(R.string.extra_body),
                    hint = stringResource(R.string.extra_body_hint),
                    rows = bodyRows,
                    onChange = { bodyRows = it.toMutableList() }
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (presetSheetOpen) {
        AlertDialog(
            onDismissRequest = { presetSheetOpen = false },
            confirmButton = {
                TextButton(onClick = { presetSheetOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.pick_preset_or_manual)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProviderPresets.all.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (name.isBlank()) name = preset.name
                                    presetBaseUrl = preset.baseUrl
                                    baseUrl = preset.baseUrl
                                    providerType = preset.type
                                    // 预设可以自带非标端点路径（如 xAI → /responses），
                                    // 选中后同步到 Chat Completions 路径字段；用户
                                    // 在 UI 里清空该字段会回退到默认 /chat/completions
                                    chatCompletionsPath = preset.path
                                    presetSheetOpen = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Column {
                                Text(
                                    if (preset.type == com.miniichatNext.carter.data.ProviderType.CLAUDE)
                                        "${preset.name}  • Claude"
                                    else preset.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    preset.hint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    if (showResponseApiWarning) {
        AlertDialog(
            onDismissRequest = { showResponseApiWarning = false },
            title = { Text(stringResource(R.string.response_api)) },
            text = { Text(stringResource(R.string.response_api_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    useResponseApi = true
                    showResponseApiWarning = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResponseApiWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun FieldSection(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun EditableValue(
    value: String,
    placeholder: String,
    monospace: Boolean = false,
    onChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                )
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(
                color = LocalContentColor.current,
                fontSize = 14.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true
        )
    }
}

@Composable
private fun KeyValueEditor(
    title: String,
    hint: String,
    rows: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        rows.forEachIndexed { index, (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (k.isEmpty()) {
                        Text(stringResource(R.string.key),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                    }
                    BasicTextField(
                        value = k,
                        onValueChange = { newK ->
                            val nextList = rows.toMutableList()
                            nextList[index] = newK to v
                            onChange(nextList)
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (v.isEmpty()) {
                        Text(stringResource(R.string.value),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                    }
                    BasicTextField(
                        value = v,
                        onValueChange = { newV ->
                            val nextList = rows.toMutableList()
                            nextList[index] = k to newV
                            onChange(nextList)
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                IconButton(
                    onClick = {
                        val nextList = rows.toMutableList()
                        nextList.removeAt(index)
                        onChange(nextList)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .clickable { onChange(rows + ("" to "")) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.add_row), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

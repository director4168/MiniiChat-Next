package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.miniichatNext.carter.data.model.ProviderOverride
import com.miniichatNext.carter.data.model.ProviderType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.res.stringResource
import com.miniichatNext.carter.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import com.miniichatNext.carter.data.model.ThinkingLevel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.model.ProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String,
    activeModel: String,
    onPick: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
    // 思考等级是「模型级」覆盖；null = 清掉覆盖，回到跟随服务商
    onThinkingChange: (ThinkingLevel?) -> Unit = {},
    // Response API / 提示缓存是「对话 × 服务商」级覆盖；null = 清掉覆盖
    onResponseApiChange: (Boolean?) -> Unit = {},
    onPromptCacheChange: (Boolean?) -> Unit = {},
    /** 当前对话对该服务商的覆盖（null字段 = 跟随服务商） */
    providerOverride: ProviderOverride = ProviderOverride(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 640.dp)
        ) {
            Text(
                stringResource(R.string.setting_model),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_models),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 固定区：思考等级 + Response API / 提示缓存 ──
            // 刻意放在LazyColumn之外：滚动模型列表时这组控件不会被带走。
            // 显示的永远是「生效值」，副标题标出这是本模型的覆盖还是跟随服务商。
            val activeProvider = providers.firstOrNull { it.id == activeProviderId }
            val activeCfg = activeProvider?.model(activeModel)
            ModelQuickSettings(
                providerType = activeProvider?.type() ?: ProviderType.OPENAI,
                thinking = activeProvider?.effectiveThinking(activeModel) ?: ThinkingLevel.OFF,
                thinkingIsOverride = activeCfg?.thinkingLevel != null,
                responseApi = activeProvider?.effectiveResponseApi(providerOverride.responseApi) ?: false,
                responseApiIsOverride = providerOverride.responseApi != null,
                promptCache = activeProvider?.effectivePromptCache(providerOverride.promptCache) ?: false,
                promptCacheIsOverride = providerOverride.promptCache != null,
                onThinkingChange = onThinkingChange,
                onResponseApiChange = onResponseApiChange,
                onPromptCacheChange = onPromptCacheChange,
            )
            Spacer(Modifier.height(12.dp))

            if (providers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.model_picker_no_providers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    providers.forEach { provider ->
                        val matches = provider.modelIds().filter {
                            query.isBlank() || it.contains(query, ignoreCase = true)
                        }
                        if (matches.isNotEmpty() || query.isBlank()) {
                            item(key = "h-${provider.id}") {
                                Text(
                                    provider.name,
                                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // key带下标兜底：模型列表里万一有重名也只渲染不崩（fetchModels已去重）
                        itemsIndexed(matches, key = { i, m -> "${provider.id}::$m#$i" }) { _, model ->
                            val selected = provider.id == activeProviderId && model == activeModel
                            ModelRow(
                                label = model,
                                selected = selected,
                                onClick = { onPick(provider.id, model); onDismiss() }
                            )
                        }
                        if (provider.modelIds().isEmpty() && query.isBlank()) {
                            item(key = "empty-${provider.id}") {
                                Text(
                                    stringResource(R.string.model_picker_no_models),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 模型选择页的固定区：思考等级 + Response API（OpenAI兼容）/ 提示缓存（Anthropic）。
 *
 * 放在搜索框和模型列表之间、且在LazyColumn之外 —— 滚列表时不会跟着动。
 * 选「自动」= 不干预（清掉覆盖），其余档位会显式发给服务端。
 */
@Composable
private fun ModelQuickSettings(
    providerType: ProviderType,
    thinking: ThinkingLevel,
    thinkingIsOverride: Boolean,
    responseApi: Boolean,
    responseApiIsOverride: Boolean,
    promptCache: Boolean,
    promptCacheIsOverride: Boolean,
    onThinkingChange: (ThinkingLevel?) -> Unit,
    onResponseApiChange: (Boolean?) -> Unit,
    onPromptCacheChange: (Boolean?) -> Unit,
) {
    var showApiWarning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            stringResource(R.string.thinking_level),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            // 「自动」就是「跟随/不干预」—— 所以不再单独要一个「跟随服务商」chip
            ThinkingLevel.entries.forEach { lvl ->
                val selected = if (lvl == ThinkingLevel.AUTO) {
                    !thinkingIsOverride || thinking == ThinkingLevel.AUTO
                } else {
                    thinkingIsOverride && thinking == lvl
                }
                QuickChip(
                    label = when (lvl) {
                        ThinkingLevel.OFF -> stringResource(R.string.thinking_off)
                        ThinkingLevel.AUTO -> stringResource(R.string.thinking_auto)
                        ThinkingLevel.LOW -> stringResource(R.string.thinking_low)
                        ThinkingLevel.MEDIUM -> stringResource(R.string.thinking_medium)
                        ThinkingLevel.HIGH -> stringResource(R.string.thinking_high)
                    },
                    selected = selected,
                    // 选「自动」= 清掉覆盖（回到默认）
                    onClick = { onThinkingChange(if (lvl == ThinkingLevel.AUTO) null else lvl) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (providerType == ProviderType.CLAUDE) {
            // Anthropic：提示缓存
            QuickSwitchRow(
                title = stringResource(R.string.prompt_cache),
                subtitle = if (promptCacheIsOverride) stringResource(R.string.override_in_this_chat)
                else stringResource(R.string.follow_provider_setting),
                checked = promptCache,
                onCheckedChange = onPromptCacheChange,
            )
        } else {
            // OpenAI兼容：Response API（高风险，开之前先提示）
            QuickSwitchRow(
                title = stringResource(R.string.response_api),
                subtitle = if (responseApiIsOverride) stringResource(R.string.override_in_this_chat)
                else stringResource(R.string.follow_provider_setting),
                checked = responseApi,
                onCheckedChange = { v ->
                    if (v && !responseApi) showApiWarning = true else onResponseApiChange(v)
                },
            )
        }
    }

    if (showApiWarning) {
        AlertDialog(
            onDismissRequest = { showApiWarning = false },
            title = { Text(stringResource(R.string.response_api)) },
            text = { Text(stringResource(R.string.response_api_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    onResponseApiChange(true)
                    showApiWarning = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showApiWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun QuickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun QuickSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable

internal fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

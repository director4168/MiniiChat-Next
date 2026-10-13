package com.miniichatNext.carter.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderType
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SettingsTopBar

/** 单个模型的属性覆盖（连接/温度/最大输出/能力/ResponseAPI或缓存；null=跟随服务商） */
@Composable
fun ModelEditScreen(
    provider: ProviderConfig,
    modelId: String,
    onBack: () -> Unit,
    onSave: (ModelConfig) -> Unit,
) {
    val existing = provider.model(modelId) ?: ModelConfig(modelId = modelId)
    val isClaude = provider.type() == ProviderType.CLAUDE
    var baseUrl by remember(modelId) { mutableStateOf(existing.baseUrl.orEmpty()) }
    var apiPath by remember(modelId) { mutableStateOf(existing.apiPath.orEmpty()) }
    var apiKey by remember(modelId) { mutableStateOf(existing.apiKey.orEmpty()) }
    var temperature by remember(modelId) { mutableStateOf(existing.temperature) }
    var maxTokens by remember(modelId) { mutableStateOf(existing.maxTokens) }
    var supportsReasoning by remember(modelId) { mutableStateOf(existing.supportsReasoning) }
    var supportsToolCall by remember(modelId) { mutableStateOf(existing.supportsToolCall) }
    var supportsImageInput by remember(modelId) { mutableStateOf(existing.supportsImageInput) }
    var responseApi by remember(modelId) { mutableStateOf(existing.responseApi) }
    var promptCache by remember(modelId) { mutableStateOf(existing.promptCache) }
    var promptCacheTtl by remember(modelId) { mutableStateOf(existing.promptCacheTtl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = stringResource(R.string.model_edit_title),
            onBack = onBack,
            actions = {
                TextButton(onClick = {
                    onSave(
                        existing.copy(
                            baseUrl = baseUrl.takeIf { it.isNotBlank() },
                            apiPath = apiPath.takeIf { it.isNotBlank() },
                            apiKey = apiKey.takeIf { it.isNotBlank() },
                            temperature = temperature,
                            maxTokens = maxTokens,
                            supportsReasoning = supportsReasoning,
                            supportsToolCall = supportsToolCall,
                            supportsImageInput = supportsImageInput,
                            responseApi = responseApi,
                            promptCache = promptCache,
                            promptCacheTtl = promptCacheTtl,
                        )
                    )
                    onBack()
                }) { Text(stringResource(R.string.action_save)) }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.model_edit_override_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 连接覆盖（baseUrl/API路径/API Key；留空跟随服务商）
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        title = stringResource(R.string.model_base_url),
                        value = baseUrl,
                        placeholder = provider.baseUrl,
                        onChange = { baseUrl = it },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        title = stringResource(R.string.model_api_path),
                        value = apiPath,
                        placeholder = provider.chatCompletionsPath,
                        onChange = { apiPath = it },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        title = stringResource(R.string.model_api_key),
                        value = apiKey,
                        placeholder = stringResource(R.string.model_follow_provider),
                        onChange = { apiKey = it },
                    )
                }
            }

            // 温度/最大输出
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    TemperatureSlider(
                        value = temperature,
                        onChange = { temperature = it },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))
                    NumberField(
                        title = stringResource(R.string.max_tokens),
                        value = maxTokens?.toString() ?: "",
                        placeholder = "8192",
                        onChange = { maxTokens = it.toIntOrNull() },
                    )
                }
            }

            // Response API（OpenAI型）或提示缓存（Claude型）——模型级默认，优先级低于对话级
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (isClaude) {
                        BooleanTriSwitch(
                            title = stringResource(R.string.prompt_cache),
                            value = promptCache,
                            onFollow = { promptCache = null },
                            onTrue = { promptCache = true },
                            onFalse = { promptCache = false },
                        )
                        if (promptCache == true) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val follow = promptCacheTtl == null
                                TextButton(onClick = { promptCacheTtl = null }) {
                                    Text(
                                        stringResource(R.string.model_follow_provider),
                                        color = if (follow) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                listOf(
                                    com.miniichatNext.carter.data.model.PromptCacheTtl.FIVE_MINUTES to stringResource(R.string.cache_ttl_5m),
                                    com.miniichatNext.carter.data.model.PromptCacheTtl.ONE_HOUR to stringResource(R.string.cache_ttl_1h),
                                ).forEach { (ttl, label) ->
                                    val sel = promptCacheTtl == ttl.name
                                    TextButton(onClick = { promptCacheTtl = ttl.name }) {
                                        Text(
                                            label,
                                            color = if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        BooleanTriSwitch(
                            title = stringResource(R.string.response_api),
                            value = responseApi,
                            onFollow = { responseApi = null },
                            onTrue = { responseApi = true },
                            onFalse = { responseApi = false },
                        )
                    }
                }
            }

            // 模型能力标记
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    AbilitySwitch(
                        title = stringResource(R.string.model_supports_reasoning),
                        checked = supportsReasoning,
                        onCheckedChange = { supportsReasoning = it },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    AbilitySwitch(
                        title = stringResource(R.string.model_supports_tool_call),
                        checked = supportsToolCall,
                        onCheckedChange = { supportsToolCall = it },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    AbilitySwitch(
                        title = stringResource(R.string.model_supports_image_input),
                        checked = supportsImageInput,
                        onCheckedChange = { supportsImageInput = it },
                    )
                }
            }

            // 重置
            TextButton(onClick = {
                baseUrl = ""
                apiPath = ""
                apiKey = ""
                temperature = null
                maxTokens = null
                supportsReasoning = true
                supportsToolCall = true
                supportsImageInput = true
                responseApi = null
                promptCache = null
                promptCacheTtl = null
            }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.model_edit_clear))
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 三态开关：跟随服务商(null)/开/关 */
@Composable
private fun BooleanTriSwitch(
    title: String,
    value: Boolean?,
    onFollow: () -> Unit,
    onTrue: () -> Unit,
    onFalse: () -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val follow = value == null
            TextButton(onClick = onFollow) {
                Text(
                    stringResource(R.string.model_follow_provider),
                    color = if (follow) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val on = value == true
            TextButton(onClick = onTrue) {
                Text(
                    stringResource(R.string.action_on),
                    color = if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val off = value == false
            TextButton(onClick = onFalse) {
                Text(
                    stringResource(R.string.action_off),
                    color = if (off) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 温度滑动条（0.0-2.0，步长0.1；拖到最左=跟随服务商） */
@Composable
private fun TemperatureSlider(
    value: Float?,
    onChange: (Float?) -> Unit,
) {
    val follow = value == null
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.setting_temperature),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                value?.toString() ?: stringResource(R.string.model_follow_provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(null) }) {
                Text(
                    stringResource(R.string.model_follow_provider),
                    color = if (follow) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = (value ?: 1.0f).coerceIn(0f, 2f),
                onValueChange = { onChange((it * 10).toInt() / 10f) },
                valueRange = 0f..2f,
                steps = 19,
                enabled = !follow,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TextField(
    title: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                color = androidx.compose.material3.LocalContentColor.current
            ),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun NumberField(
    title: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                color = androidx.compose.material3.LocalContentColor.current
            ),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box {
                    if (value.isEmpty()) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun AbilitySwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

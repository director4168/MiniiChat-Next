/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.localmodel

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.localmodel.LocalModelParameters
import com.miniichatNext.carter.data.localmodel.LocalModelRecord
import kotlin.math.roundToInt

@Composable
internal fun LocalModelParametersDialog(model: LocalModelRecord, saving: Boolean, onDismiss: () -> Unit,
    onSave: (String, LocalModelParameters) -> Unit) {
    val params = model.parameters
    var name by rememberSaveable(model.id) { mutableStateOf(model.name) }
    var temperature by rememberSaveable(model.id) { mutableStateOf(params.temperature) }
    var topP by rememberSaveable(model.id) { mutableStateOf(params.topP) }
    var topK by rememberSaveable(model.id) { mutableStateOf(params.topK.toString()) }
    var penalty by rememberSaveable(model.id) { mutableStateOf(params.repeatPenalty) }
    var maxTokens by rememberSaveable(model.id) { mutableStateOf(params.maxTokens.toString()) }
    var contextSize by rememberSaveable(model.id) { mutableStateOf(params.contextSize.toString()) }
    var threads by rememberSaveable(model.id) { mutableStateOf(params.threads) }
    var seed by rememberSaveable(model.id) { mutableStateOf(params.seed.toString()) }
    val edited = LocalModelParameters(
        temperature = temperature, topP = topP, topK = topK.toIntOrNull() ?: -1,
        repeatPenalty = penalty, maxTokens = maxTokens.toIntOrNull()?.coerceAtMost(maxTokensLimit(contextSize)) ?: 0,
        contextSize = contextSize.toIntOrNull() ?: 0, threads = threads, seed = seed.toIntOrNull() ?: -2,
    )
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.local_model_parameters)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(160) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_model_name)) })
                ParameterSlider(temperature, { temperature = it }, R.string.local_model_temperature, 0f..2f, 100, !saving)
                ParameterSlider(topP, { topP = it }, R.string.local_model_top_p, 0.01f..1f, 100, !saving)
                IntParameterField(topK, { topK = it }, R.string.local_model_top_k, 0, 200)
                ParameterSlider(penalty, { penalty = it }, R.string.local_model_penalty, 0.5f..2f, 100, !saving)
                IntParameterField(maxTokens, { maxTokens = it }, R.string.local_model_max_tokens, 1, maxTokensLimit(contextSize))
                IntParameterField(contextSize, { contextSize = it }, R.string.local_model_context, 256, 16384, onCommit = { context ->
                    maxTokens = limitInteger(maxTokens, maxTokensLimit(context), false)
                })
                ParameterSlider(threads.toFloat(), { threads = it.roundToInt() }, R.string.local_model_threads, 1f..16f, 1, !saving)
                IntParameterField(seed, { seed = it }, R.string.local_model_seed,
                    -1, Int.MAX_VALUE, KeyboardType.Text, true)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && edited.isValid() && !saving,
            onClick = { onSave(name, edited) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ParameterSlider(value: Float, onChange: (Float) -> Unit, @StringRes label: Int,
    range: ClosedFloatingPointRange<Float>, scale: Int, enabled: Boolean) {
    val title = stringResource(label)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(if (scale == 1) value.roundToInt().toString() else value.toString(),
                style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange((it * scale).roundToInt().toFloat() / scale) },
            modifier = Modifier.semantics { contentDescription = title },
            enabled = enabled,
            valueRange = range,
            steps = if (scale == 1) (range.endInclusive - range.start).roundToInt() - 1 else 0,
        )
    }
}

@Composable
private fun IntParameterField(value: String, onChange: (String) -> Unit, @StringRes label: Int,
    min: Int, max: Int, keyboardType: KeyboardType = KeyboardType.Number, allowNegative: Boolean = false,
    onCommit: (String) -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(value, { raw ->
        val digits = if (allowNegative) raw.removePrefix("-") else raw
        if (digits.all { it in '0'..'9' }) onChange(limitInteger(raw, max, allowNegative))
    }, singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused) {
                val clamped = value.toIntOrNull()?.coerceIn(min, max)?.toString() ?: value
                onChange(clamped)
                onCommit(clamped)
            }
            focused = state.isFocused
        },
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}

private fun maxTokensLimit(context: String): Int =
    ((context.toIntOrNull() ?: 2048).coerceIn(256, 16384)) / 2

private fun limitInteger(raw: String, max: Int, allowNegative: Boolean): String {
    if (raw.isEmpty()) return raw
    val negative = allowNegative && raw.startsWith('-')
    val digits = raw.drop(if (negative) 1 else 0)
    if (digits.isEmpty()) return if (negative) "-" else ""
    if (digits.any { it !in '0'..'9' }) return ""
    val magnitude = digits.trimStart('0').ifEmpty { "0" }
    val parsed = magnitude.toLongOrNull() ?: Long.MAX_VALUE
    return (if (negative) -parsed else parsed).coerceIn(if (allowNegative) -1L else 0L, max.toLong()).toString()
}

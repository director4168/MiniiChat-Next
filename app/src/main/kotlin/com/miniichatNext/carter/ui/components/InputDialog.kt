package com.miniichatNext.carter.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.miniichatNext.carter.R

/**
 * 通用输入弹窗：title+OutlinedTextField+确认/取消。
 * 替代Drawer/ProviderEditor/Skills/Workspace等多处手写输入框弹窗模板。
 *
 * @param validator 输入校验：返回非空=错误信息（确认按钮disabled并显示）
 * @param keyboardType 键盘类型（默认文本）
 * @param singleLine 是否单行（默认true，按Enter自动确认）
 */
@Composable
fun InputDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    confirmText: String = stringResource(R.string.action_save),
    dismissText: String = stringResource(R.string.action_cancel),
    validator: (String) -> String? = { null },
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    // key=initial：同一组合位置复用弹窗（如连续重命名不同对象）时，initial变化要重置输入
    var input by remember(initial) { mutableStateOf(initial) }
    val err = validator(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(label) },
                singleLine = singleLine,
                isError = err != null,
                supportingText = err?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank() && err == null,
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

package com.miniichatNext.carter.debug.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.components.ConfirmDeleteDialog
import com.miniichatNext.carter.ui.components.InputDialog
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.util.openUrl
import com.miniichatNext.carter.util.rememberCopyToClipboard
import com.miniichatNext.carter.vm.ChatViewModel
import android.widget.Toast
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

/**
 * 弹窗类型测试页（独立于DebugScreen）：
 * 1) Snackbar三通道（toast/error/aux）—复用真实错误流
 * 2)各种弹窗：MdDialog/ConfirmDeleteDialog/InputDialog/自定义内容
 * 3)复用组件测试：AssetJsonLoader/openUrl/copyToClipboard
 */
@Composable
fun DialogTestScreen(
    onBack: () -> Unit,
    vm: ChatViewModel? = null,
) {
    val context = LocalContext.current
    val copy = rememberCopyToClipboard()

    var showConfirmDelete by remember { mutableStateOf(false) }
    var showInputDialog by remember { mutableStateOf(false) }
    var showOpenUrlDialog by remember { mutableStateOf(false) }
    var showCustomContent by remember { mutableStateOf("") }
    var inputResult by remember { mutableStateOf<String?>(null) }
    var deleteDialogText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues()),
    ) {
        SettingsTopBar(
            title = stringResource(R.string.debug_dialog_test_screen),
            onBack = onBack,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(stringResource(R.string.debug_dialog_section), style = MaterialTheme.typography.titleMedium)
            SectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.debug_dialog_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm?.setToast(context.getString(R.string.debug_dialog_sample_toast)) }) {
                            Text(stringResource(R.string.debug_dialog_error_toast))
                        }
                        TextButton(onClick = { vm?.setError(context.getString(R.string.debug_dialog_sample_error)) }) {
                            Text(stringResource(R.string.debug_dialog_error_dialog))
                        }
                        TextButton(onClick = { vm?.setAuxError("suggestion:" + context.getString(R.string.debug_dialog_sample_aux)) }) {
                            Text(stringResource(R.string.debug_dialog_aux_toast))
                        }
                    }
                }
            }

            Text(stringResource(R.string.debug_dialog_test_section_popups), style = MaterialTheme.typography.titleMedium)
            SectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var showMd by remember { mutableStateOf(false) }
                    TextButton(onClick = { showMd = true }) { Text(stringResource(R.string.debug_dialog_test_md)) }
                    if (showMd) {
                        com.miniichatNext.carter.ui.components.MdDialog(
                            title = stringResource(R.string.debug_test_md_title),
                            text = stringResource(R.string.debug_test_md_body),
                            confirmText = stringResource(R.string.action_ok),
                            onConfirm = { showMd = false },
                            onDismiss = { showMd = false },
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    TextButton(onClick = { showConfirmDelete = true }) {
                        Text(stringResource(R.string.debug_dialog_test_confirm_delete))
                    }
                    if (showConfirmDelete) {
                        val targetName = stringResource(R.string.debug_test_target_name)
                        val deletedFmt = stringResource(R.string.debug_test_deleted_at, System.currentTimeMillis().toInt() % 100000)
                        ConfirmDeleteDialog(
                            targetName = targetName,
                            onConfirm = {
                                showConfirmDelete = false
                                deleteDialogText = deletedFmt
                            },
                            onDismiss = { showConfirmDelete = false },
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    TextButton(onClick = { showInputDialog = true }) {
                        Text(stringResource(R.string.debug_dialog_test_input))
                    }
                    if (showInputDialog) {
                        InputDialog(
                            title = stringResource(R.string.debug_test_input_name),
                            label = stringResource(R.string.debug_test_input_label),
                            onConfirm = { value ->
                                showInputDialog = false
                                inputResult = value
                            },
                            onDismiss = { showInputDialog = false },
                            validator = { if (it.length < 2) context.getString(R.string.debug_test_input_min2) else null },
                        )
                    }
                    inputResult?.let {
                        Text(stringResource(R.string.debug_test_input_typed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    var customContent by remember { mutableStateOf("") }
                    // 输入框左占大部分+发送按钮右侧同一高度
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = customContent,
                            onValueChange = { customContent = it },
                            label = { Text(stringResource(R.string.debug_dialog_custom_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = customContent.isNotBlank(),
                            onClick = { showCustomContent = customContent },
                        ) { Text(stringResource(R.string.debug_dialog_custom_send)) }
                    }
                }
            }

            Text(stringResource(R.string.debug_test_reuse), style = MaterialTheme.typography.titleMedium)
            SectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val list = com.miniichatNext.carter.util.AssetJsonLoader.load(
                            context, "data/projects.json",
                            ListSerializer(com.miniichatNext.carter.data.model.ProjectCredit.serializer()),
                        ) ?: emptyList()
                        Toast.makeText(
                            context,
                            context.getString(R.string.debug_test_asset_json_ok, list.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(stringResource(R.string.debug_test_asset_json)) }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    TextButton(onClick = { showOpenUrlDialog = true }) {
                        Text(stringResource(R.string.debug_test_open_url))
                    }
                    if (showOpenUrlDialog) {
                        var url by remember { mutableStateOf("https://www.example.com") }
                        InputDialog(
                            title = stringResource(R.string.debug_test_open_url_dialog_title),
                            label = stringResource(R.string.debug_test_open_url_dialog_label),
                            initial = url,
                            onConfirm = { value ->
                                showOpenUrlDialog = false
                                DebugLog.i("Debug", "openUrl: $value")
                                context.openUrl(value)
                            },
                            onDismiss = { showOpenUrlDialog = false },
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    var copyText by remember { mutableStateOf("") }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = copyText,
                            onValueChange = { copyText = it },
                            label = { Text(stringResource(R.string.debug_test_copy_text_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = copyText.isNotBlank(),
                            onClick = { copy(copyText) },
                        ) { Text(stringResource(R.string.debug_test_copy)) }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // "已删除"普通md弹窗（onConfirm后触发），非警告样式
    deleteDialogText?.let { text ->
        com.miniichatNext.carter.ui.components.MdDialog(
            text = text,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { deleteDialogText = null },
            onDismiss = { deleteDialogText = null },
        )
    }

    if (showCustomContent.isNotEmpty()) {
        com.miniichatNext.carter.ui.components.MdDialog(
            title = showCustomContent.take(40),
            text = showCustomContent,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { showCustomContent = "" },
            onDismiss = { showCustomContent = "" },
        )
    }
}

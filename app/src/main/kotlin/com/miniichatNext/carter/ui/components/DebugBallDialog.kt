package com.miniichatNext.carter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.debug.DebugLevel
import com.miniichatNext.carter.debug.DebugLog

/**
 * 调试球弹窗（MdDialog风格）：折叠区块（日志等级/快速跳转/界面设置），
 * 点标题行展开或收起；"打开Debug页"直接应用内导航。
 */
@Composable
fun DebugBallDialog(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
    onOpenDebugPage: () -> Unit,
) {
    val context = LocalContext.current
    var logExpanded by remember { mutableStateOf(false) }
    var jumpExpanded by remember { mutableStateOf(false) }
    var uiExpanded by remember { mutableStateOf(false) }
    // DebugLog.level是静态属性不触发重组——本地state记录选中档，点击即刷新高亮
    var currentLevel by remember { mutableStateOf(DebugLog.level) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_ball_title)) },
        text = {
            Column {
                SectionHeader(
                    label = stringResource(R.string.debug_ball_log_level),
                    expanded = logExpanded,
                    onClick = { logExpanded = !logExpanded },
                )
                if (logExpanded) {
                    DebugLevel.entries.forEach { lvl ->
                        OptionRow(
                            label = lvl.name,
                            selected = lvl == currentLevel,
                            onClick = {
                                DebugLog.setLevel(context, lvl)
                                currentLevel = lvl
                            },
                        )
                    }
                }
                SectionHeader(
                    label = stringResource(R.string.debug_ball_quick_jump),
                    expanded = jumpExpanded,
                    onClick = { jumpExpanded = !jumpExpanded },
                )
                if (jumpExpanded) {
                    OptionRow(
                        label = stringResource(R.string.debug_ball_open_debug_page),
                        selected = false,
                        onClick = {
                            onOpenDebugPage()
                            onDismiss()
                        },
                    )
                }
                SectionHeader(
                    label = stringResource(R.string.debug_ball_ui_settings),
                    expanded = uiExpanded,
                    onClick = { uiExpanded = !uiExpanded },
                )
                if (uiExpanded) {
                    Text(
                        text = stringResource(R.string.setting_language),
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(
                        "system" to stringResource(R.string.setting_language_system),
                        "zh" to stringResource(R.string.setting_language_zh),
                        "en" to stringResource(R.string.setting_language_en),
                    ).forEach { (key, label) ->
                        OptionRow(
                            label = label,
                            selected = settings.language == key,
                            onClick = { onUpdate { s -> s.copy(language = key) } },
                        )
                    }
                    Text(
                        text = stringResource(R.string.setting_theme_mode),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(
                        "system" to stringResource(R.string.setting_theme_system),
                        "light" to stringResource(R.string.setting_theme_light),
                        "dark" to stringResource(R.string.setting_theme_dark),
                    ).forEach { (key, label) ->
                        OptionRow(
                            label = label,
                            selected = settings.themeMode == key,
                            onClick = { onUpdate { s -> s.copy(themeMode = key) } },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 折叠区块标题行（▸/▾随展开状态） */
@Composable
private fun SectionHeader(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = (if (expanded) "▾ " else "▸ ") + label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 选项行（选中高亮主色） */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
    )
}

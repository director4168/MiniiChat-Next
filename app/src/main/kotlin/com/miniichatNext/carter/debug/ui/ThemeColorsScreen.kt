/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.debug.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.AppThemeOverrides
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * Debug配色修改页：预览+按角色编辑覆盖色+导入导出（JSON）+恢复默认。
 * 覆盖按深浅两套分别存，保存即全局生效（AppThemeOverrides.version驱动Theme重载）。
 */
@Serializable
private data class ThemeOverrideFile(
    val light: Map<String, String> = emptyMap(),
    val dark: Map<String, String> = emptyMap(),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThemeColorsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lightMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var darkMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    val copy = com.miniichatNext.carter.util.rememberCopyToClipboard()

    // 打开页面先预填当前配色，再叠加已保存的覆盖（顺序不能反，否则动态取色被静态默认色压死）
    LaunchedEffect(Unit) {
        val saved = AppThemeOverrides.load(context)
        lightMap = AppThemeOverrides.currentSchemeHexes(dark = false) + saved["light"].orEmpty()
        darkMap = AppThemeOverrides.currentSchemeHexes(dark = true) + saved["dark"].orEmpty()
    }

    var showToolsSheet by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun doSave() {
        AppThemeOverrides.save(context, lightMap, darkMap)
        notice = context.getString(R.string.debug_theme_saved)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(
            stringResource(R.string.debug_theme_title),
            onBack,
            actions = {
                IconButton(onClick = { doSave() }) {
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.debug_theme_preview),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    ThemePreviewRow(mode = "light", overrides = lightMap)
                    Spacer(Modifier.height(8.dp))
                    ThemePreviewRow(mode = "dark", overrides = darkMap)
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.debug_theme_edit),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            AppThemeOverrides.ROLE_GROUPS.forEach { (group, roles) ->
                SectionCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(roleGroupTitleRes(group)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        roles.forEachIndexed { index, role ->
                            RoleEditRow(
                                role = role,
                                lightHex = lightMap[role].orEmpty(),
                                darkHex = darkMap[role].orEmpty(),
                                onLightChange = { v -> lightMap = lightMap + (role to v) },
                                onDarkChange = { v -> darkMap = darkMap + (role to v) },
                            )
                            if (index < roles.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Spacer(Modifier.height(96.dp))
        }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = { showToolsSheet = true },
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Default.Build, contentDescription = stringResource(R.string.debug_theme_tools))
        }
        // 必须条件组合：ModalBottomSheet没有显隐参数，无条件组合会进页即弹+关闭后卡死
        if (showToolsSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            containerColor = MaterialTheme.colorScheme.background
        ) {
            SheetActionRow(
                icon = Icons.Default.Save,
                label = stringResource(R.string.action_save),
                onClick = { showToolsSheet = false; doSave() }
            )
            SheetActionRow(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.debug_theme_reset_defaults),
                onClick = { showToolsSheet = false; showResetConfirm = true }
            )
            SheetActionRow(
                icon = Icons.Default.FileDownload,
                label = stringResource(R.string.debug_theme_export),
                onClick = {
                    showToolsSheet = false
                    val json = AppJson.json.encodeToString(
                        ThemeOverrideFile.serializer(),
                        ThemeOverrideFile(lightMap, darkMap)
                    )
                    copy(json)
                    notice = context.getString(R.string.debug_theme_exported)
                }
            )
            SheetActionRow(
                icon = Icons.Default.FileUpload,
                label = stringResource(R.string.debug_theme_import),
                onClick = { showToolsSheet = false; showImportDialog = true }
            )
            Spacer(Modifier.height(12.dp))
        }
        }
    }

    if (showImportDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.debug_theme_import)) },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    placeholder = { Text(stringResource(R.string.debug_theme_import_hint)) },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    val parsed = AppJson.parse(importText)?.let {
                        runCatching {
                            AppJson.json.decodeFromJsonElement(ThemeOverrideFile.serializer(), it)
                        }.getOrNull()
                    }
                    if (parsed == null) {
                        notice = context.getString(R.string.debug_theme_import_failed)
                    } else {
                        lightMap = AppThemeOverrides.currentSchemeHexes(dark = false) + AppThemeOverrides.normalizeRoles(parsed.light)
                        darkMap = AppThemeOverrides.currentSchemeHexes(dark = true) + AppThemeOverrides.normalizeRoles(parsed.dark)
                        AppThemeOverrides.save(context, lightMap, darkMap)
                        notice = context.getString(R.string.debug_theme_imported)
                    }
                }) { Text(stringResource(R.string.debug_theme_import_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showResetConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.debug_theme_reset_confirm_title)) },
            text = { Text(stringResource(R.string.debug_theme_reset_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    AppThemeOverrides.clear(context)
                    lightMap = AppThemeOverrides.currentSchemeHexes(dark = false)
                    darkMap = AppThemeOverrides.currentSchemeHexes(dark = true)
                    notice = context.getString(R.string.debug_theme_reset)
                }) { Text(stringResource(R.string.debug_theme_reset_defaults)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** 单行角色编辑：左侧上ID下说明，右侧浅色/深色hex输入+色块 */
@Composable
private fun RoleEditRow(
    role: String,
    lightHex: String,
    darkHex: String,
    onLightChange: (String) -> Unit,
    onDarkChange: (String) -> Unit,
) {
    var pickerMode by remember { mutableStateOf<String?>(null) } // null / "light" / "dark"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .width(116.dp)
                .heightIn(max = 48.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                role,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            )
            roleDescriptionRes(role)?.let { res ->
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = lightHex,
            onValueChange = onLightChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("-", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            leadingIcon = {
                AlphaSwatch(AppThemeOverrides.parseHex(lightHex)) { pickerMode = "light" }
            }
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = darkHex,
            onValueChange = onDarkChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("-", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            leadingIcon = {
                AlphaSwatch(AppThemeOverrides.parseHex(darkHex)) { pickerMode = "dark" }
            }
        )
    }
    when (pickerMode) {
        "light" -> ColorPickerDialog(
            title = "$role (light)",
            initialHex = lightHex,
            onConfirm = { onLightChange(it); pickerMode = null },
            onDismiss = { pickerMode = null }
        )
        "dark" -> ColorPickerDialog(
            title = "$role (dark)",
            initialHex = darkHex,
            onConfirm = { onDarkChange(it); pickerMode = null },
            onDismiss = { pickerMode = null }
        )
    }
}

private fun roleGroupTitleRes(group: String): Int = when (group) {
    "primary" -> R.string.debug_theme_group_primary
    "surfaces" -> R.string.debug_theme_group_surfaces
    "outlines" -> R.string.debug_theme_group_outlines
    "status" -> R.string.debug_theme_group_status
    "messageNavigation" -> R.string.debug_theme_group_message_navigation
    "logSearch" -> R.string.debug_log_search_colors
    else -> error("Unknown theme role group: $group")
}

/** 角色说明走资源，左侧显示ID为主，说明作为副标题 */
private fun roleDescriptionRes(role: String): Int? = when (role) {
    "primary" -> R.string.debug_role_primary
    "onPrimary" -> R.string.debug_role_on_primary
    "primaryContainer" -> R.string.debug_role_primary_container
    "onPrimaryContainer" -> R.string.debug_role_on_primary_container
    "background" -> R.string.debug_role_background
    "onBackground" -> R.string.debug_role_on_background
    "surface" -> R.string.debug_role_surface
    "onSurface" -> R.string.debug_role_on_surface
    "surfaceVariant" -> R.string.debug_role_surface_variant
    "onSurfaceVariant" -> R.string.debug_role_on_surface_variant
    "outline" -> R.string.debug_role_outline
    "outlineVariant" -> R.string.debug_role_outline_variant
    "error" -> R.string.debug_role_error
    "onError" -> R.string.debug_role_on_error
    "warning" -> R.string.debug_role_warning
    "warningAction" -> R.string.debug_role_warning_action
    "messageJumperBackground" -> R.string.debug_message_jumper_background
    "messageJumperContent" -> R.string.debug_message_jumper_content
    "logSearchHighlight" -> R.string.debug_log_search_highlight
    "logSearchBackground" -> R.string.debug_log_search_background
    else -> null
}

/** 带透明度预览的色块：棋盘衬底，半透明色才看得出来 */
@Composable
private fun AlphaSwatch(color: Color?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .checkerboard(5.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize().background(color ?: MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** 透明色预览底纹 */
private fun Modifier.checkerboard(cell: Dp = 8.dp): Modifier = drawBehind {
    val step = cell.toPx()
    val columns = ceil(size.width / step).toInt()
    val rows = ceil(size.height / step).toInt()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            drawRect(
                color = if ((row + column) % 2 == 0) Color(0xFFE8E8E8) else Color(0xFFC9C9C9),
                topLeft = Offset(column * step, row * step),
                size = Size(step, step),
            )
        }
    }
}

/**
 * RGBa调色盘对话框：R/G/B/A四滑条+实时预览+hex只读显示。
 * 初始值来自当前hex（非法则黑）；确认时以#RRGGBB或#AARRGGBB写回。
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    initialHex: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = AppThemeOverrides.parseHex(initialHex) ?: Color.Black
    var a by remember { mutableStateOf((initial.alpha * 255f).roundToInt()) }
    var r by remember { mutableStateOf(((initial.red * 255).toInt())) }
    var g by remember { mutableStateOf(((initial.green * 255).toInt())) }
    var b by remember { mutableStateOf(((initial.blue * 255).toInt())) }
    val current = Color(r, g, b, a)
    val hex = if (a >= 255) "#%02X%02X%02X".format(r, g, b)
    else "#%02X%02X%02X%02X".format(a, r, g, b)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .checkerboard(10.dp)
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                ) {
                    Box(Modifier.fillMaxSize().background(current))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    hex,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                SliderRow("R", r, android.graphics.Color.RED) { r = it }
                SliderRow("G", g, android.graphics.Color.GREEN) { g = it }
                SliderRow("B", b, android.graphics.Color.BLUE) { b = it }
                SliderRow("A", a, android.graphics.Color.GRAY) { a = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hex) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SliderRow(label: String, value: Int, color: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(color),
                activeTrackColor = Color(color).copy(alpha = 0.6f)
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(36.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun ColorSwatch(color: Color?) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}

/** 预览行：固定样例组件用覆盖色绘制（模拟真实按钮/文字观感） */
@Composable
private fun ThemePreviewRow(mode: String, overrides: Map<String, String>) {
    val base = if (mode == "dark") {
        MaterialTheme.colorScheme // 深色主题下用真实scheme作为底
    } else {
        MaterialTheme.colorScheme
    }
    val c = AppThemeOverrides.apply(base, overrides)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.background)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(c.primary)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "primary / " + (overrides["primary"] ?: "-"),
                style = MaterialTheme.typography.bodySmall,
                color = c.onBackground
            )
            Text(
                stringResource(R.string.debug_theme_preview_sample),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("OK", style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer)
        }
    }
}

/** 底部折叠框里的动作行：图标+文字，整行可点 */
@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

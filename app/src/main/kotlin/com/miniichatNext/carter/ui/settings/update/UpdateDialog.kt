package com.miniichatNext.carter.ui.settings.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateInfo
import com.miniichatNext.carter.data.update.displayVersion
import com.miniichatNext.carter.data.update.localVersionCode
import com.miniichatNext.carter.data.update.remoteVersionCode
import com.miniichatNext.carter.ui.markdown.BubblePalette
import com.miniichatNext.carter.ui.markdown.LightContentPalette
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.ui.theme.LocalDarkMode

/**
 * 自定义按钮：未来扩展用。可传任意原生按钮回调，
 * 让更新弹窗能加入"复制下载链接/稍后提醒/加入Beta测试"等额外操作。
 */
data class UpdateAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 更新内容弹窗：展示版本说明+[忽略此版本][下载]。
 * 点"下载"→onStartDownload(info)（调用方关闭本弹窗并打开独立的下载进度弹窗）。
 *
 * 通过[customActions]在内置按钮右侧追加额外按钮。
 * 弹窗关闭后由调用方清理状态（UpdateState.clearPending等）。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo?,
    currentVersion: String?,
    onDismiss: () -> Unit,
    /** 用户点"忽略此版本"时调用（一般用于把version持久化到ignoredVersions） */
    onIgnore: (() -> Unit)? = null,
    customActions: List<UpdateAction> = emptyList(),
    onFullscreen: ((String, Boolean) -> Unit)? = null,
    /** 点"下载"：调用方负责关闭本弹窗并开始独立下载弹窗流程 */
    onStartDownload: (UpdateInfo) -> Unit = {},
) {
    if (info == null) return
    val context = LocalContext.current
    val ver = currentVersion ?: "?"
    // 弹窗背景跟随主题（themeMode的实际结果，含App内手动深色）：浅色主题下用浅底palette，否则引用块/代码框会与背景撞色
    val mdPalette = if (LocalDarkMode.current) BubblePalette else LightContentPalette
    // 版本对比走版本码数字比较（远端versionCode vs本地longVersionCode）
    val localCode = remember { localVersionCode(context) }
    val remoteCode = remember(info) { info.remoteVersionCode() }
    val isNewer = remoteCode > localCode
    val isSame = remoteCode > 0L && remoteCode == localCode

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val displayVersion = info.displayVersion()
            Text(
                text = when {
                    !isNewer && isSame -> stringResource(R.string.update_no_update)
                    info.compulsory -> stringResource(R.string.update_force_title, displayVersion)
                    else -> stringResource(R.string.update_new_version, displayVersion)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!isNewer && isSame) {
                    Text(
                        text = stringResource(R.string.update_current_version, ver),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isNewer) {
                    if (info.time.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.update_time_prefix, info.time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (info.update_content.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            MarkdownText(
                                // 远端文案里可能写的是字面"\n"（两个字符），换成真换行，块级结构（标题/列表/公式）才能正确解析
                                text = info.update_content.replace("\\n", "\n"),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                palette = mdPalette,
                                onFullscreen = onFullscreen,
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.update_no_update),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    !isNewer -> {
                        // 已是最新：仅"关闭"
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                    else -> {
                        // [忽略此版本][下载]；强制更新时无忽略
                        if (onIgnore != null && !info.compulsory) {
                            TextButton(onClick = {
                                onIgnore.invoke()
                                onDismiss()
                            }) { Text(stringResource(R.string.update_ignore_this)) }
                            Spacer(Modifier.padding(horizontal = 4.dp))
                        }
                        Button(onClick = {
                            // 关闭内容弹窗，由调用方打开独立下载弹窗
                            onDismiss()
                            onStartDownload(info)
                        }) { Text(stringResource(R.string.update_download)) }
                    }
                }
                customActions.forEach { action ->
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        enabled = action.enabled,
                        onClick = action.onClick,
                    ) { Text(action.label) }
                }
            }
        },
    )
}

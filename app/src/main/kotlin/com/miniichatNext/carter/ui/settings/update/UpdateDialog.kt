/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.BuildConfig
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateInfo
import com.miniichatNext.carter.data.update.displayVersion
import com.miniichatNext.carter.data.update.localVersionCode
import com.miniichatNext.carter.data.update.remoteVersionCode
import com.miniichatNext.carter.ui.markdown.BubblePalette
import com.miniichatNext.carter.ui.markdown.LightContentPalette
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.miniichatNext.carter.util.currentPackageAbi

data class UpdateAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** 更新内容底部模态弹窗。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UpdateDialog(
    info: UpdateInfo?,
    currentVersion: String?,
    onDismiss: () -> Unit,
    onIgnore: (() -> Unit)? = null,
    customActions: List<UpdateAction> = emptyList(),
    onFullscreen: ((String, Boolean) -> Unit)? = null,
    onStartDownload: (UpdateInfo) -> Unit = {},
) {
    if (info == null) return
    val context = LocalContext.current
    val ver = currentVersion ?: BuildConfig.VERSION_NAME
    val packageAbi = remember(context) { currentPackageAbi(context) }
    val mdPalette = if (LocalDarkMode.current) BubblePalette else LightContentPalette
    val localCode = remember { localVersionCode(context) }
    val remoteCode = remember(info) { info.remoteVersionCode() }
    val isNewer = remoteCode > localCode
    val isSame = remoteCode > 0L && remoteCode == localCode
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            val displayVersion = info.displayVersion()
            Text(
                text = when {
                    !isNewer && isSame -> stringResource(R.string.update_no_update)
                    info.compulsory -> stringResource(R.string.update_force_title, displayVersion)
                    else -> stringResource(R.string.update_new_version, displayVersion)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!isNewer && isSame) {
                    Text(stringResource(R.string.update_current_version, ver), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (isNewer) {
                    if (info.time.isNotBlank()) {
                        Text(stringResource(R.string.update_time_prefix, info.time), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (info.update_content.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            MarkdownText(
                                text = info.update_content.replace("\\n", "\n"),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                palette = mdPalette,
                                onFullscreen = onFullscreen,
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.update_no_update), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_current_build, ver, localCode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = packageAbi,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!isNewer) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_close)) }
                    } else {
                        if (onIgnore != null && !info.compulsory) {
                            TextButton(onClick = { onIgnore(); onDismiss() }) { Text(stringResource(R.string.update_ignore_this)) }
                        }
                        Button(onClick = { onDismiss(); onStartDownload(info) }) {
                            Text(stringResource(R.string.update_download))
                        }
                    }
                    customActions.forEach { action ->
                        TextButton(enabled = action.enabled, onClick = action.onClick) { Text(action.label) }
                    }
                }
            }
        }
    }
}

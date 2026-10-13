package com.miniichatNext.carter.ui.settings.update

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateDownloader
import com.miniichatNext.carter.data.update.UpdateInfo
import com.miniichatNext.carter.data.update.UpdateInstaller
import com.miniichatNext.carter.data.update.UpdateRepository
import com.miniichatNext.carter.data.update.compareVersions
import com.miniichatNext.carter.ui.markdown.BubblePalette
import com.miniichatNext.carter.ui.markdown.LightContentPalette
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 * 更新弹窗：检查/下载/安装三阶段共用一个弹窗。
 *
 * 按钮区按阶段切换：
 * - Idle→[忽略此版本][下载]
 * - Failed→[忽略此版本][重试下载]
 * - Done (下载成功，未安装)→[忽略此版本][立即安装]/[删除]
 *
 * 通过[customActions]在内置按钮右侧追加额外按钮。
 *
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
) {
    if (info == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver = currentVersion ?: "?"
    // 弹窗背景跟随主题（themeMode的实际结果，含App内手动深色）：浅色主题下用浅底palette，否则引用块/代码框会与背景撞色
    val mdPalette = if (LocalDarkMode.current) BubblePalette else LightContentPalette
    val cmp = remember(info.version, ver) { compareVersions(info.version, ver) }
    val isNewer = cmp > 0
    val isSame = cmp == 0
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var lastError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (phase !is Phase.Downloading && phase !is Phase.Verifying) onDismiss()
        },
        title = {
            val displayVersion = info.versionCode.ifBlank { info.version }
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
                    Spacer(Modifier.height(8.dp))
                    when (val p = phase) {
                        is Phase.Idle, is Phase.Failed, is Phase.Done -> { /* 占位 */ }
                        is Phase.Downloading -> DownloadProgressView(
                            bytesRead = p.progress.bytesRead,
                            totalBytes = p.progress.totalBytes,
                            bytesPerSec = p.progress.bytesPerSec,
                        )
                        is Phase.Verifying -> Text(
                            text = stringResource(R.string.update_verifying),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    lastError?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
            val downloadInProgress = phase is Phase.Downloading || phase is Phase.Verifying
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    !isNewer -> {
                        // 已是最新：仅"关闭"
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                    info.compulsory -> {
                        // 强制更新：仅"下载"（或Done后变"立即安装"）
                        CompulsoryActionButton(
                            info = info,
                            phase = phase,
                            downloadInProgress = downloadInProgress,
                            context = context,
                            scope = scope,
                            onPhase = { phase = it },
                            onError = { lastError = it },
                            onDismiss = onDismiss,
                        )
                    }
                    else -> {
                        // 可选更新：[忽略此版本][下载/重试/立即安装]
                        if (onIgnore != null) {
                            TextButton(
                                enabled = !downloadInProgress,
                                onClick = {
                                    onIgnore.invoke()
                                    onDismiss()
                                }
                            ) { Text(stringResource(R.string.update_ignore_this)) }
                            Spacer(Modifier.padding(horizontal = 4.dp))
                        }
                        OptionalActionButton(
                            info = info,
                            phase = phase,
                            downloadInProgress = downloadInProgress,
                            context = context,
                            scope = scope,
                            onPhase = { phase = it },
                            onError = { lastError = it },
                            onDismiss = onDismiss,
                        )
                    }
                }
                customActions.forEach { action ->
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        enabled = action.enabled && !downloadInProgress,
                        onClick = action.onClick,
                    ) { Text(action.label) }
                }
            }
        },
    )

    // Phase.Done自动安装尝试一次
    LaunchedEffect(phase) {
        val done = phase as? Phase.Done ?: return@LaunchedEffect
        // 只在用户没点安装按钮时自动触发一次（标志：installAttempted）
        if (!done.installAttempted) {
            val launched = withContext(Dispatchers.IO) {
                UpdateInstaller.install(context, done.file)
            }
            if (launched) {
                // 安装器接管后，关闭弹窗
                onDismiss()
            } else {
                lastError = context.getString(R.string.update_install_failed_retry)
                // installAttempted已在Phase.Done data class默认false→改true
                phase = Phase.Done(done.file, installAttempted = true, installFailed = true)
            }
        }
    }
}

/**
 * 非强制更新时的右按钮：根据phase切换文案+动作
 * - Idle/Failed→触发下载
 * - Done→触发安装（重试）
 */
@Composable
private fun OptionalActionButton(
    info: UpdateInfo,
    phase: Phase,
    downloadInProgress: Boolean,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onPhase: (Phase) -> Unit,
    onError: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val (label, onClickAction) = when (phase) {
        is Phase.Done -> {
            stringResource(R.string.update_install_now) to {
                scope.launch {
                    val launched = withContext(Dispatchers.IO) {
                        UpdateInstaller.install(context, phase.file)
                    }
                    if (launched) {
                        onDismiss()
                    } else {
                        onError(context.getString(R.string.update_install_failed))
                        onPhase(Phase.Done(phase.file, installAttempted = true, installFailed = true))
                    }
                }
                Unit
            }
        }
        is Phase.Failed -> {
            val failedFile = phase.file
            if (failedFile != null) {
                // 校验失败但文件保留→[强制安装]
                context.getString(R.string.update_force_install) to {
                    scope.launch {
                        val launched = withContext(Dispatchers.IO) {
                            UpdateInstaller.install(context, failedFile)
                        }
                        if (launched) onDismiss()
                        else onError(context.getString(R.string.update_install_failed))
                    }
                    Unit
                }
            } else {
                // 非SHA错误（HTTP/网络），没有本地文件→[重试下载]
                stringResource(R.string.update_retry_download) to {
                    onError(null)
                    startDownload(context, info, scope, onPhase, onError)
                    Unit
                }
            }
        }
        else -> {
            stringResource(R.string.update_download) to {
                startDownload(context, info, scope, onPhase, onError)
                Unit
            }
        }
    }
    Button(
        enabled = !downloadInProgress,
        onClick = onClickAction,
    ) { Text(label) }
}

/**
 * 强制更新时的右按钮：永远只有"下载"或"立即安装"
 */
@Composable
private fun CompulsoryActionButton(
    info: UpdateInfo,
    phase: Phase,
    downloadInProgress: Boolean,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onPhase: (Phase) -> Unit,
    onError: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val (label, onClickAction) = when (phase) {
        is Phase.Done -> {
            stringResource(R.string.update_install_now) to {
                scope.launch {
                    val launched = withContext(Dispatchers.IO) {
                        UpdateInstaller.install(context, phase.file)
                    }
                    if (launched) {
                        onDismiss()
                    } else {
                        onError(context.getString(R.string.update_install_failed_retry))
                        onPhase(Phase.Done(phase.file, installAttempted = true, installFailed = true))
                    }
                }
                Unit
            }
        }
        is Phase.Failed -> {
            stringResource(R.string.update_retry_download) to {
                onError(null)
                startDownload(context, info, scope, onPhase, onError)
                Unit
            }
        }
        else -> {
            stringResource(R.string.update_download) to {
                startDownload(context, info, scope, onPhase, onError)
                Unit
            }
        }
    }
    Button(
        enabled = !downloadInProgress,
        onClick = onClickAction,
    ) { Text(label) }
}

private sealed class Phase {
    object Idle : Phase()
    data class Downloading(val progress: UpdateDownloader.Progress) : Phase()
    object Verifying : Phase()
    /**
     * @param installAttempted 弹窗出现后是否尝试过自动安装（用于避免重复触发）
     * @param installFailed 自动安装失败→显示错误行+重试按钮
     */
    data class Done(
        val file: File,
        val installAttempted: Boolean = false,
        val installFailed: Boolean = false,
    ) : Phase()
    /**
     * @param message 错误描述（"校验失败：..."或HTTP/网络错误）
     * @param file 仅SHA校验失败时携带（文件保留在磁盘，可让用户绕过SHA强制安装）；
     * 其他失败为null。
     */
    data class Failed(val message: String, val file: File? = null) : Phase()
}

@Composable
private fun DownloadProgressView(
    bytesRead: Long,
    totalBytes: Long,
    bytesPerSec: Long,
) {
    val progress = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatBytes(bytesRead) + (if (totalBytes > 0) " / ${formatBytes(totalBytes)}" else ""),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (bytesPerSec > 0) "${formatBytes(bytesPerSec)}/s" else "—",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun startDownload(
    context: Context,
    info: UpdateInfo,
    scope: kotlinx.coroutines.CoroutineScope,
    onPhase: (Phase) -> Unit,
    onError: (String?) -> Unit,
) {
    onError(null)
    val apkFile = UpdateInstaller.cacheApk(context)
    onPhase(Phase.Downloading(UpdateDownloader.Progress(0, -1, 0)))
    UpdateDownloader.start(
        context = context,
        scope = scope,
        url = info.DownloadUrl,
        targetFile = apkFile,
        expectedSha256 = info.SHA256,
        onProgress = { p -> onPhase(Phase.Downloading(p)) },
        onResult = { result ->
            when (result) {
                is UpdateDownloader.Result.Success -> {
                    onPhase(Phase.Done(result.apkFile))
                }
                is UpdateDownloader.Result.Failure -> {
                    // 传递apkFile（可能为null）让UpdateDialog在Phase.Failed时知道是否有文件可强制安装
                    onPhase(Phase.Failed(result.message, result.apkFile))
                    onError(result.message)
                }
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
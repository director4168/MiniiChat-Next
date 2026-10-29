package com.miniichatNext.carter.ui.settings.update

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateDownloader
import com.miniichatNext.carter.data.update.UpdateInfo
import com.miniichatNext.carter.data.update.UpdateInstaller
import com.miniichatNext.carter.data.update.displayVersion
import com.miniichatNext.carter.util.AppForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 独立下载弹窗的状态（提升到AppRoot级，内容弹窗关闭后下载继续）。
 * 下载+校验成功：App在前台→自动拉起安装器；在后台→ReadyToInstall（回前台由用户点安装，
 * 因为Android 10+后台启动Activity会被系统静默拦截）。
 */
sealed class UpdateDownloadState {
    data class Downloading(
        val version: String,
        val progress: UpdateDownloader.Progress,
    ) : UpdateDownloadState()

    /** 已下载且SHA通过，但App当时在后台，等待用户点"立即安装" */
    data class ReadyToInstall(
        val version: String,
        val file: File,
        val downloadLocation: String? = null,
        val publishedToDownloads: Boolean = false,
    ) : UpdateDownloadState()

    data class Failed(
        val version: String,
        val message: String,
        /** SHA校验失败时保留文件，可"强制安装"；HTTP/网络错误为null */
        val file: File? = null,
        val downloadLocation: String? = null,
    ) : UpdateDownloadState()
}

/**
 * 启动下载流程：进度→SHA校验（downloader内）→成功时按前后台决定自动安装或待安装。
 * onResult已在主线程回调（UpdateDownloader.start内部withContext(Main)）。
 */
fun startUpdateDownload(
    context: Context,
    scope: CoroutineScope,
    info: UpdateInfo,
    onState: (UpdateDownloadState) -> Unit,
    onFinished: () -> Unit,
    onInstallNow: (File) -> Unit,
) {
    val version = info.displayVersion()
    com.miniichatNext.carter.debug.DebugLog.i("Update", "startDownload: url=" + info.DownloadUrl.take(80))
    try {
        onState(UpdateDownloadState.Downloading(version, UpdateDownloader.Progress(0, -1, 0)))
        val apkFile = UpdateInstaller.cacheApk(context)
        UpdateDownloader.start(
            context = context,
            scope = scope,
            url = info.DownloadUrl,
            targetFile = apkFile,
            expectedSha256 = info.SHA256,
            onProgress = { p ->
                scope.launch(Dispatchers.Main.immediate) {
                    onState(UpdateDownloadState.Downloading(version, p))
                }
            },
            onResult = { result ->
                com.miniichatNext.carter.debug.DebugLog.i("Update", "download result: " + result::class.simpleName)
                when (result) {
                    is UpdateDownloader.Result.Success -> {
                        scope.launch(Dispatchers.IO) {
                            val published = runCatching {
                                UpdateInstaller.publishToDownloads(context, result.apkFile, version)
                            }.onFailure { com.miniichatNext.carter.debug.DebugLog.e("Update", "publish update to Downloads failed", it) }
                            val location = published.getOrNull()
                            withContext(Dispatchers.Main) {
                                if (AppForeground.isForeground()) onInstallNow(result.apkFile)
                                else {
                                    com.miniichatNext.carter.debug.DebugLog.i("Update", "background, wait for manual install")
                                    onState(UpdateDownloadState.ReadyToInstall(version, result.apkFile, location,
                                        publishedToDownloads = published.isSuccess && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q))
                                }
                            }
                        }
                    }
                    is UpdateDownloader.Result.Failure -> {
                        onState(UpdateDownloadState.Failed(version, result.message, result.apkFile))
                    }
                }
            }
        )
    } catch (t: Throwable) {
        com.miniichatNext.carter.debug.DebugLog.e("Update", "startDownload threw", t)
        onState(UpdateDownloadState.Failed(version, t.message ?: t::class.java.simpleName ?: "unknown", null))
    }
}

/**
 * 独立下载进度弹窗：进度条+字节+速度；完成后自动安装（前台）或显示[立即安装]；
 * 失败时[重试][强制安装(可选)][关闭]。下载中不可关闭（防止误触中断）。
 */
@Composable
fun UpdateDownloadDialog(
    state: UpdateDownloadState,
    onRetry: () -> Unit,
    onForceInstall: (File) -> Unit,
    onInstallNow: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (state !is UpdateDownloadState.Downloading) onDismiss() },
        title = {
            Text(
                text = when (state) {
                    is UpdateDownloadState.Downloading ->
                        stringResource(R.string.update_downloading_title, state.version)
                    is UpdateDownloadState.ReadyToInstall ->
                        stringResource(R.string.update_download_done_title)
                    is UpdateDownloadState.Failed -> stringResource(R.string.update_download_failed_title)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is UpdateDownloadState.Downloading -> {
                        val p = state.progress
                        val frac = if (p.totalBytes > 0) (p.bytesRead.toFloat() / p.totalBytes).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatBytes(p.bytesRead) + (if (p.totalBytes > 0) " / ${formatBytes(p.totalBytes)}" else ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (p.bytesPerSec > 0) formatBytes(p.bytesPerSec) + "/s" else "...",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is UpdateDownloadState.ReadyToInstall -> {
                        Text(text = state.version, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.downloadLocation?.let { location ->
                            Text(text = stringResource(
                                if (state.publishedToDownloads) R.string.update_saved_location else R.string.update_saved_copy,
                                location), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is UpdateDownloadState.Failed -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                when (state) {
                    is UpdateDownloadState.ReadyToInstall -> {
                        Button(onClick = { onInstallNow(state.file) }) {
                            Text(stringResource(R.string.update_install_now))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                    is UpdateDownloadState.Failed -> {
                        if (state.file != null) {
                            val failedFile = state.file
                            TextButton(onClick = { onForceInstall(failedFile) }) {
                                Text(stringResource(R.string.update_force_install))
                            }
                        }
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.update_retry_download))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                    is UpdateDownloadState.Downloading -> Unit
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocalModelImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val uri = intent?.data
        val url = intent?.getStringExtra(URL)
        if (uri == null && url == null) { stopSelf(); return START_NOT_STICKY }
        val startPhase = if (uri != null) LocalModelImportPhase.COPYING else LocalModelImportPhase.DOWNLOADING
        val initial = LocalModelImportState(phase = startPhase)
        try {
            check(LocalModelStore.supported) { getString(R.string.local_model_unsupported) }
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.local_model_title), NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION, notification(initial))
        } catch (e: Exception) {
            update.value = LocalModelImportState(phase = LocalModelImportPhase.FAILED,
                error = e.localizedMessage ?: getString(R.string.local_model_import_failed))
            stopSelf()
            return START_NOT_STICKY
        }
        update.value = initial
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var finalState = LocalModelImportState(phase = LocalModelImportPhase.FAILED,
                error = getString(R.string.local_model_import_failed))
            try {
                val importer = LocalModelImporter(applicationContext)
                var lastNotification = 0L
                var lastBytes = 0L
                var lastAt = SystemClock.elapsedRealtime()
                var speed = 0L
                val progress: (Long, Long) -> Unit = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAt >= 500L) {
                        val delta = (bytes - lastBytes).coerceAtLeast(0L) * 1000L / (now - lastAt).coerceAtLeast(1L)
                        speed = if (speed == 0L) delta else (speed * 2 + delta) / 3
                        lastBytes = bytes
                        lastAt = now
                    }
                    val state = update.value.copy(bytes = bytes, total = total, bytesPerSec = speed)
                    update.value = state
                    if (now - lastNotification >= 700L) {
                        notifications.notify(NOTIFICATION, notification(state))
                        lastNotification = now
                    }
                }
                val onSource: (String, Long) -> Unit = { name, total ->
                    val state = update.value.copy(fileName = name, total = total)
                    update.value = state
                    notifications.notify(NOTIFICATION, notification(state))
                    lastAt = SystemClock.elapsedRealtime()
                    lastNotification = lastAt
                }
                val onVerifying: () -> Unit = {
                    val state = update.value.copy(phase = LocalModelImportPhase.VERIFYING, bytesPerSec = 0L)
                    update.value = state
                    notifications.notify(NOTIFICATION, notification(state))
                }
                val record = if (uri != null) importer.importFile(uri, progress, onSource, onVerifying)
                    else importer.download(requireNotNull(url), progress, onSource, onVerifying)
                finalState = update.value.copy(phase = LocalModelImportPhase.DONE, importedName = record.name)
            } catch (e: CancellationException) {
                finalState = LocalModelImportState(phase = LocalModelImportPhase.CANCELLED,
                    error = getString(R.string.update_cancelled))
            } catch (e: Exception) {
                finalState = LocalModelImportState(phase = LocalModelImportPhase.FAILED,
                    error = e.localizedMessage ?: getString(R.string.local_model_import_failed))
            } catch (_: LinkageError) {
                finalState = LocalModelImportState(phase = LocalModelImportPhase.FAILED,
                    error = getString(R.string.local_model_load_failed))
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    update.value = finalState
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: LocalModelImportState): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(OPEN, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, NOTIFICATION, cancelIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val title = when (state.phase) {
            LocalModelImportPhase.VERIFYING -> getString(R.string.local_model_verifying)
            LocalModelImportPhase.DOWNLOADING -> getString(R.string.local_model_downloading)
            LocalModelImportPhase.COPYING -> getString(R.string.local_model_copying)
            else -> getString(R.string.local_model_importing)
        }
        val detail = when {
            state.phase == LocalModelImportPhase.VERIFYING -> getString(R.string.local_model_verifying)
            state.total > 0 -> getString(R.string.local_model_transfer_progress,
                Formatter.formatFileSize(this, state.bytes), Formatter.formatFileSize(this, state.total),
                Formatter.formatFileSize(this, state.bytesPerSec))
            else -> getString(R.string.local_model_transfer_progress, Formatter.formatFileSize(this, state.bytes),
                getString(R.string.local_model_unknown_size), Formatter.formatFileSize(this, state.bytesPerSec))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail)
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
            .setProgress(100, if (state.total > 0) (100.0 * state.bytes / state.total).toInt().coerceIn(0, 100) else 0,
                state.total <= 0 || state.phase == LocalModelImportPhase.VERIFYING)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), cancel)
            .build()
    }

    internal companion object {
        const val OPEN = "open_local_models"
        private const val URL = "url"
        private const val CHANNEL = "local_model_import"
        private const val NOTIFICATION = 1450
        private const val CANCEL = "com.miniichatNext.carter.CANCEL_MODEL_IMPORT"
        private val update = MutableStateFlow(LocalModelImportState())
        val state = update.asStateFlow()
        private val dialog = MutableStateFlow(false)
        val visible = dialog.asStateFlow()

        fun hide() { dialog.value = false }

        fun show() {
            if (update.value.phase != LocalModelImportPhase.IDLE) dialog.value = true
        }

        fun start(context: Context, uri: Uri? = null, url: String? = null) {
            if (update.value.active) {
                show()
                return
            }
            val intent = Intent(context, LocalModelImportService::class.java).setData(uri).putExtra(URL, url)
            if (uri != null) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            update.value = LocalModelImportState(phase = if (uri != null) LocalModelImportPhase.COPYING else LocalModelImportPhase.DOWNLOADING)
            dialog.value = true
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                update.value = LocalModelImportState(phase = LocalModelImportPhase.FAILED,
                    error = e.localizedMessage ?: context.getString(R.string.local_model_import_failed))
            }
        }

        fun cancelIntent(context: Context) = Intent(context, LocalModelImportService::class.java).setAction(CANCEL)
    }
}

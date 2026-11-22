/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Rootfs下载前台服务：让系统通知接管进度，并保证应用退到后台后安装继续。 */
class RootfsDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_WORKSPACE_ID)
            if (id != null) scope.launch { RootfsInstallManager.cancelAndJoin(id) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val workspaceId = intent?.getStringExtra(EXTRA_WORKSPACE_ID) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(workspaceId, null))
        observation?.cancel()
        observation = scope.launch {
            RootfsInstallManager.states.collectLatest { states ->
                val state = states[workspaceId]
                if (state == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val progress = (state as? RootfsInstallState.Running)?.progress
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, buildNotification(workspaceId, progress))
                if (state !is RootfsInstallState.Running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observation?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.workspace_rootfs_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.workspace_rootfs_notification_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(workspaceId: String, progress: RootfsInstallProgress?): Notification {
        val total = progress?.totalBytes
        val read = progress?.bytesRead ?: 0L
        val percent = if (total != null && total > 0) ((read * 100 / total).coerceIn(0, 100)).toInt() else 0
        val title = getString(R.string.workspace_rootfs_notification_title)
        val text = when (progress?.stage) {
            RootfsInstallStage.DOWNLOADING -> if (total != null && total > 0) {
                getString(R.string.workspace_rootfs_notification_progress, read / 1024 / 1024, total / 1024 / 1024)
            } else getString(R.string.workspace_rootfs_notification_downloading, read / 1024 / 1024)
            RootfsInstallStage.EXTRACTING -> getString(R.string.workspace_rootfs_notification_extracting)
            RootfsInstallStage.INSTALLED -> getString(R.string.workspace_rootfs_notification_finished)
            null -> getString(R.string.workspace_rootfs_notification_waiting)
        }
        val open = PendingIntent.getActivity(
            this, workspaceId.hashCode(), Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, workspaceId.hashCode() xor 0x1F,
            Intent(this, RootfsDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_WORKSPACE_ID, workspaceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total == null || total <= 0)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rootfs_download"
        private const val NOTIFICATION_ID = 0x524F
        private const val EXTRA_WORKSPACE_ID = "workspace_id"
        private const val ACTION_CANCEL = "com.miniichatNext.carter.action.CANCEL_ROOTFS_DOWNLOAD"

        fun start(context: Context, workspaceId: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RootfsDownloadService::class.java)
                        .putExtra(EXTRA_WORKSPACE_ID, workspaceId),
                )
            }
        }
    }
}

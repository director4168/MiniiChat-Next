/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.store.migration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationMigrationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        if (ConversationMigration.flow.value.phase != ConversationMigration.Phase.STARTING) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.migration_title), NotificationManager.IMPORTANCE_LOW))
            val current = notification(ConversationMigration.flow.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, current, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, current)
        } catch (_: Exception) {
            ConversationMigration.fail(R.string.migration_service_failed)
            stopSelf()
            return START_NOT_STICKY
        }
        job = scope.launch {
            var previous: ConversationMigration.Phase? = null
            var previousTime = 0L
            val observer = launch {
                ConversationMigration.flow.collect { state ->
                    if (state.phase in FINISHED) return@collect
                    val now = SystemClock.elapsedRealtime()
                    if (state.phase != previous || now - previousTime >= 1000L) {
                        runCatching { manager.notify(NOTIFICATION_ID, notification(state)) }
                        previous = state.phase
                        previousTime = now
                    }
                }
            }
            try {
                runCatching {
                    wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:conversation-migration")
                        .apply { acquire(15 * 60 * 1000L) }
                }
                ConversationMigration.run(this@ConversationMigrationService)
            } finally {
                observer.cancel()
                endForeground()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        ConversationMigration.fail(R.string.migration_interrupted)
        endForeground()
        stopSelf()
    }

    override fun onDestroy() {
        if (job?.isActive == true && ConversationMigration.flow.value.phase !in FINISHED) {
            ConversationMigration.fail(R.string.migration_interrupted)
        }
        scope.cancel()
        endForeground()
        super.onDestroy()
    }

    private fun endForeground() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(state: ConversationMigration.Status): Notification {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = if (state.total > 0 && state.phase == ConversationMigration.Phase.WRITING) {
            getString(R.string.migration_notification_progress, state.processed, state.total)
        } else getString(state.phase.label)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.migration_title))
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress((state.total + 1).coerceAtLeast(1), state.processed, state.total == 0 || state.phase != ConversationMigration.Phase.WRITING)
            .build()
    }

    companion object {
        private const val CHANNEL = "conversation_migration"
        private const val NOTIFICATION_ID = 1501
        private val FINISHED = setOf(ConversationMigration.Phase.READY, ConversationMigration.Phase.WARNINGS, ConversationMigration.Phase.FAILED)

        internal fun start(context: Context) {
            if (!ConversationMigration.claimService()) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConversationMigrationService::class.java))
            } catch (_: Exception) {
                ConversationMigration.fail(R.string.migration_service_failed)
            }
        }
    }
}

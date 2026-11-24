/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppForeground
import com.miniichatNext.carter.util.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var downloader: UpdateSourceDownloader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var snapshot: UpdateDownloadSnapshot? = null
    private var lastNotification = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateDownloadSession.serviceRunning = true
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_LOW))
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            downloader?.cancel()
            work?.cancel()
            if (work == null) stopSelf()
            return START_NOT_STICKY
        }
        if (work?.isActive == true) return START_NOT_STICKY
        val info = runCatching { AppJson.json.decodeFromString(UpdateInfo.serializer(), intent?.getStringExtra(INFO).orEmpty()) }.getOrNull()
        if (info == null) { stopSelf(); return START_NOT_STICKY }
        if (cancelRequested) {
            cancelRequested = false
            UpdateDownloadSession.update(UpdateDownloadSnapshot(info, phase = UpdatePhase.CANCELLED, message = getString(R.string.update_cancelled), probes = emptyList()))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        snapshot = UpdateDownloadSnapshot(info)
        val notification = notification(snapshot!!)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
        } catch (failure: Exception) {
            UpdateDownloadSession.update(snapshot!!.copy(phase = UpdatePhase.FAILED, message = failure.message.orEmpty()))
            stopSelf()
            return START_NOT_STICKY
        }
        // 启动后立刻取消也要进入finally，避免前台通知和服务留存。
        work = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:update").apply { acquire(30 * 60 * 1000L) }
                download(info)
            } catch (cancel: CancellationException) {
                snapshot?.let { publish(it.copy(phase = UpdatePhase.CANCELLED, message = getString(R.string.update_cancelled), probes = emptyList()), true) }
            } catch (failure: UpdateVerificationFailure) {
                try {
                    val retained = UpdateArtifactCache(this@UpdateDownloadService).retainFailed(failure.file)
                    snapshot?.let { publish(it.copy(phase = UpdatePhase.VERIFICATION_FAILED, message = failure.message.orEmpty(), probes = emptyList(),
                        file = retained.first, forceDigest = retained.second, autoInstall = false), true) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (retention: Exception) {
                    snapshot?.let { publish(it.copy(phase = UpdatePhase.VERIFICATION_FAILED, message = retention.message.orEmpty(), probes = emptyList(), file = null, autoInstall = false), true) }
                }
            } catch (failure: Exception) {
                DebugLog.w("Update", "update download failed: ${failure.message}")
                snapshot?.let { publish(it.copy(phase = it.phase.failed(), message = failure.message.orEmpty()), true) }
            } finally {
                downloader?.cancel()
                if (snapshot?.phase != UpdatePhase.PUBLICATION_FAILED) runCatching { UpdateArtifactCache(this@UpdateDownloadService).partial.delete() }
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    snapshot?.takeUnless { it.phase == UpdatePhase.CANCELLED }
                        ?.let { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(it)) }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(info: UpdateInfo) {
        val selectedPackage = info.packageForDevice()
        require(selectedPackage.downloadUrl.isNotBlank()) { getString(R.string.update_package_missing, selectedPackage.abi) }
        require(info.remoteVersionCode() > localVersionCode(this)) { "Update version is not newer than the installed app" }
        require(Regex("[a-fA-F0-9]{64}").matches(selectedPackage.sha256.trim())) { "A valid published SHA-256 is required" }
        val cache = UpdateArtifactCache(this)
        publish(UpdateDownloadSnapshot(info), true)
        val reusable = cache.reusable(info, selectedPackage)
        if (reusable != null) {
            publish(UpdateDownloadSnapshot(info, phase = UpdatePhase.READY, file = cache.apk, reused = true,
                autoInstall = AppForeground.isForeground() && UpdateDownloadSession.visible.value), true)
            return
        }
        val settings = SettingsRepository(this).settings.first()
        val nodes = if (settings.updateNodesInvalid) emptyList() else settings.updateNodes
        val sources = UpdateDownloadSources.candidates(selectedPackage.downloadUrl, settings.updateNodesEnabled, nodes)
        // 测速走固定文件；真实APK仍按节点名映射回原下载地址。
        val probes = UpdateDownloadSources.probeCandidates(settings.updateNodesEnabled, nodes)
            .filter { probe -> sources.any { it.key == probe.key } }
        DebugLog.i("Update", "nodesEnabled=${settings.updateNodesEnabled} candidates=${sources.size} probes=${probes.size}")
        val transfer = UpdateSourceDownloader().also { downloader = it }
        val selected = if (sources.size == 1) {
            sources.single()
        } else {
            require(probes.isNotEmpty()) { "No probe source for the selected download" }
            publish(UpdateDownloadSnapshot(info, phase = UpdatePhase.PROBING, sourceCount = probes.size,
                probes = probes.map { UpdateNodeProbe(it.key) }), true)
            val results = transfer.probe(probes, onStarted = { source ->
                synchronized(this) {
                    snapshot?.let { publish(it.copy(source = source.key, probes = it.probes.withProbeStarted(source.key))) }
                }
            }) { result, completed, count ->
                synchronized(this) {
                    snapshot?.let { publish(it.copy(phase = UpdatePhase.PROBING, source = result.key,
                        tested = maxOf(completed, it.tested), sourceCount = count, probes = it.probes.withProbeResult(result))) }
                }
            }
            val best = UpdateDownloadSources.ranked(results).firstOrNull()
                ?: error(results.joinToString("\n") { "${it.key}: ${it.error}" })
            sources.firstOrNull { it.key == best.key } ?: sources.first()
        }
        publish(UpdateDownloadSnapshot(info, phase = UpdatePhase.DOWNLOADING, source = selected.key, tested = 1, sourceCount = 1,
            direct = selected.url == selectedPackage.downloadUrl), true)
        transfer.download(selected, cache.partial, onProgress = { progress ->
            snapshot?.let { publish(it.copy(phase = UpdatePhase.DOWNLOADING, progress = progress)) }
        }, verify = { file ->
            snapshot?.let { publish(it.copy(phase = UpdatePhase.VERIFYING), true) }
            cache.validate(file, info, selectedPackage)
        })
        val metadata = try { cache.commit(info, selectedPackage) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: UpdateVerificationFailure) { throw failure }
        catch (failure: Exception) {
            snapshot?.let { publish(it.copy(phase = UpdatePhase.PUBLICATION_FAILED, message = failure.message.orEmpty()), true) }
            return
        }
        val location = runCatching { UpdateInstaller.publishToDownloads(this, cache.apk, info.displayVersion(), selectedPackage.abi) }
            .onFailure { DebugLog.w("Update", "publish copy failed: ${it.message}") }.getOrNull()
        if (location != null) runCatching { cache.write(metadata.copy(downloadLocation = location)) }
        publish(UpdateDownloadSnapshot(info, phase = UpdatePhase.READY, file = cache.apk, source = selected.key,
            autoInstall = AppForeground.isForeground() && UpdateDownloadSession.visible.value,
            direct = selected.url == selectedPackage.downloadUrl), true)
    }

    @Synchronized
    private fun publish(value: UpdateDownloadSnapshot, force: Boolean = false) {
        snapshot = value
        UpdateDownloadSession.update(value)
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastNotification >= 1000) {
            lastNotification = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(value))
        }
    }

    private fun notification(state: UpdateDownloadSnapshot): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OPEN_DOWNLOAD, true)
        val pending = PendingIntent.getActivity(this, 1401, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = when (state.phase) {
            UpdatePhase.CHECKING_CACHE -> getString(R.string.update_checking_cache)
            UpdatePhase.PROBING -> getString(R.string.update_probing_nodes, state.tested, state.sourceCount)
            UpdatePhase.DOWNLOADING -> (if (state.direct) getString(R.string.update_direct_download)
                else getString(R.string.update_using_node, state.source)) +
                " · " + java.lang.String.format(java.util.Locale.ROOT, "%.1f KB/s", state.progress.bytesPerSec / 1024.0)
            UpdatePhase.VERIFYING -> getString(R.string.update_verifying_package)
            UpdatePhase.READY -> getString(R.string.update_download_done_title)
            UpdatePhase.CANCELLED -> getString(R.string.update_cancelled)
            UpdatePhase.FAILED, UpdatePhase.PROBE_FAILED, UpdatePhase.VERIFICATION_FAILED, UpdatePhase.PUBLICATION_FAILED -> state.message.take(160)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(when (state.phase) {
                UpdatePhase.PROBING -> getString(R.string.update_probe_title)
                UpdatePhase.PROBE_FAILED -> getString(R.string.update_probe_failed_title)
                UpdatePhase.VERIFYING -> getString(R.string.update_check_install_title)
                UpdatePhase.VERIFICATION_FAILED -> getString(R.string.update_verification_failed_title)
                UpdatePhase.PUBLICATION_FAILED -> getString(R.string.update_cache_publish_failed)
                UpdatePhase.CANCELLED -> getString(R.string.update_cancelled)
                UpdatePhase.READY -> getString(R.string.update_download_done_title)
                UpdatePhase.FAILED -> getString(R.string.update_download_failed_title)
                else -> getString(R.string.update_downloading_title, state.info.displayVersion())
            })
            .setContentText(content).setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pending).setOnlyAlertOnce(true).setOngoing(state.active).setAutoCancel(!state.active)
        if (state.active) {
            val progress = state.progress
            val percent = if (progress.totalBytes > 0) (progress.bytesRead.toDouble() * 100 / progress.totalBytes).toInt().coerceIn(0, 100) else 0
            builder.setProgress(100, percent, state.phase != UpdatePhase.DOWNLOADING || progress.totalBytes <= 0)
            val cancel = PendingIntent.getService(this, 1402, Intent(this, UpdateDownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.update_cancel_download), cancel)
        } else if (state.phase == UpdatePhase.READY) {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            builder.addAction(android.R.drawable.stat_sys_download_done, getString(R.string.update_install_now), pending)
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloader?.cancel()
        work?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        UpdateDownloadSession.serviceRunning = false
        if (instance === this) instance = null
        downloader?.cancel()
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: UpdateDownloadService? = null
        @Volatile private var cancelRequested = false

        fun requestCancel() {
            val active = instance
            if (active != null) {
                if (active.work == null) cancelRequested = true
                active.downloader?.cancel()
                active.work?.cancel()
            } else if (UpdateDownloadSession.state.value?.active == true) {
                cancelRequested = true
            }
        }

        const val INFO = "update_info"
        const val CANCEL = "com.miniichatNext.carter.UPDATE_CANCEL"
        const val OPEN_DOWNLOAD = "open_update_download"
        private const val CHANNEL = "apk_update_download"
        private const val NOTIFICATION = 1401
    }
}

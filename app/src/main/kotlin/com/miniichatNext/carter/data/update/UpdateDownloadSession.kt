/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.miniichatNext.carter.R
import com.miniichatNext.carter.util.AppJson
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class UpdateDownloadSnapshot(
    val info: UpdateInfo,
    val phase: UpdatePhase = UpdatePhase.CHECKING_CACHE,
    val source: String = "",
    val tested: Int = 0,
    val sourceCount: Int = 0,
    val progress: UpdateTransferProgress = UpdateTransferProgress(),
    val message: String = "",
    val file: File? = null,
    val reused: Boolean = false,
    val autoInstall: Boolean = false,
    val probes: List<UpdateNodeProbe> = emptyList(),
    val forceDigest: String? = null,
    val direct: Boolean = false,
) {
    val active: Boolean get() = phase.active
}

internal object UpdateDownloadSession {
    private val mutable = MutableStateFlow<UpdateDownloadSnapshot?>(null)
    val state = mutable.asStateFlow()
    private val shown = MutableStateFlow(false)
    val visible = shown.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreMutex = Mutex()
    @Volatile var serviceRunning = false
        internal set

    fun update(snapshot: UpdateDownloadSnapshot) { mutable.value = snapshot }
    fun show() { shown.value = true }
    fun hide() { shown.value = false }
    fun clear() { mutable.value = null; shown.value = false }

    @Synchronized
    fun start(context: Context, info: UpdateInfo) {
        if (mutable.value?.active == true || serviceRunning || installMutex.isLocked) { show(); return }
        show()
        mutable.value = UpdateDownloadSnapshot(info)
        try {
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)
                .putExtra(UpdateDownloadService.INFO, AppJson.json.encodeToString(UpdateInfo.serializer(), info)))
        } catch (failure: Exception) {
            mutable.value = UpdateDownloadSnapshot(info, phase = UpdatePhase.FAILED, message = failure.message.orEmpty())
        }
    }

    fun cancel() {
        UpdateDownloadService.requestCancel()
    }

    fun restore(context: Context, remoteInfo: UpdateInfo? = null) {
        if (mutable.value != null || serviceRunning) return
        val app = context.applicationContext
        scope.launch {
            restoreMutex.withLock {
                if (mutable.value != null || serviceRunning) return@withLock
                try {
                    val cache = UpdateArtifactCache(app)
                    val localCode = localVersionCode(app)
                    val candidates = listOfNotNull(remoteInfo, cache.read()?.info)
                        .distinct().filter { it.remoteVersionCode() > localCode }
                    for (info in candidates) {
                        if (serviceRunning || mutable.value != null) break
                        val valid = cache.reusable(info, info.packageForDevice()) ?: continue
                        mutable.compareAndSet(null, UpdateDownloadSnapshot(valid.info, phase = UpdatePhase.READY, file = cache.apk, reused = true))
                        break
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { com.miniichatNext.carter.debug.DebugLog.w("Update", "restore cached update: ${failure.message}") }
            }
        }
    }

    private val installMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun install(context: Context, automatic: Boolean = false): Boolean {
        if (!installMutex.tryLock()) return false
        try { return installVerified(context, automatic) } finally { installMutex.unlock() }
    }

    suspend fun forceInstall(context: Context): Boolean {
        if (!installMutex.tryLock()) return false
        try {
            val snapshot = mutable.value ?: return false
            if (snapshot.phase != UpdatePhase.VERIFICATION_FAILED) return false
            val file = snapshot.file ?: return false
            val hash = snapshot.forceDigest ?: return false
            return try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    UpdateArtifactCache(context.applicationContext).validateForForce(file, hash)
                }
                // 用户已在前台点击强制安装；不再依赖生命周期计数，避免部分ROM计数异常导致静默返回。
                val result = UpdateInstaller.install(context, file)
                if (result) hide() else update(snapshot.copy(message = context.getString(R.string.update_install_failed)))
                result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                update(snapshot.copy(message = failure.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.update_install_failed)))
                false
            }
        } finally { installMutex.unlock() }
    }

    private suspend fun installVerified(context: Context, automatic: Boolean): Boolean {
        val snapshot = mutable.value ?: return false
        if (snapshot.phase != UpdatePhase.READY) return false
        if (snapshot.info.remoteVersionCode() <= localVersionCode(context)) {
            clear()
            return false
        }
        return try {
            val verified = kotlinx.coroutines.withContext(Dispatchers.IO) {
                UpdateArtifactCache(context.applicationContext).reusable(snapshot.info, snapshot.info.packageForDevice())
            }
            if (verified == null) {
                update(snapshot.copy(phase = UpdatePhase.VERIFICATION_FAILED, autoInstall = false, file = null, message = context.getString(R.string.update_cached_invalid)))
                false
            } else {
                update(snapshot.copy(autoInstall = false))
                // 自动安装才要求前台；用户手动点“立即安装”时，弹窗本身已证明在前台。
                if (automatic && (!com.miniichatNext.carter.util.AppForeground.isForeground() || !visible.value)) return false
                val result = UpdateInstaller.install(context, snapshot.file ?: return false)
                if (result) hide() else update(snapshot.copy(autoInstall = false, message = context.getString(R.string.update_install_failed)))
                result
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            update(snapshot.copy(autoInstall = false, message = failure.message.orEmpty()))
            false
        }
    }
}

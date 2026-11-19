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
    @Volatile private var restoring = false
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

    @Synchronized
    fun restore(context: Context) {
        if (restoring || mutable.value != null || serviceRunning) return
        restoring = true
        scope.launch {
            try {
                val cache = UpdateArtifactCache(context.applicationContext)
                val saved = cache.read() ?: return@launch
                if (saved.info.remoteVersionCode() <= localVersionCode(context)) return@launch
                val selected = saved.info.packageForDevice()
                val valid = cache.reusable(saved.info, selected) ?: return@launch
                if (mutable.value == null && !serviceRunning) update(UpdateDownloadSnapshot(valid.info, phase = UpdatePhase.READY, file = cache.apk, reused = true))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                com.miniichatNext.carter.debug.DebugLog.w("Update", "restore cached update: ${failure.message}")
            } finally { restoring = false }
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
                    UpdateArtifactCache(context.applicationContext).validateForForce(file, hash, snapshot.info.packageForDevice())
                }
                if (!com.miniichatNext.carter.util.AppForeground.isForeground()) return false
                val result = UpdateInstaller.install(context, file)
                if (result) hide() else update(snapshot.copy(message = context.getString(R.string.update_install_failed)))
                result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { update(snapshot.copy(message = failure.message.orEmpty())); false }
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
                if (!com.miniichatNext.carter.util.AppForeground.isForeground() || (automatic && !visible.value)) return false
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

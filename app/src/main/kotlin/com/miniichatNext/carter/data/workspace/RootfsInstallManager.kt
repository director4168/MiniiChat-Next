package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** rootfs安装状态；除Running外都是终态，由页面消费后clearFinished() */
sealed interface RootfsInstallState {
    data class Running(val progress: RootfsInstallProgress?) : RootfsInstallState
    data object Success : RootfsInstallState
    data class Failure(val message: String) : RootfsInstallState
    data object Cancelled : RootfsInstallState
}

/**
 * 进程级rootfs安装任务持有者（独立于页面生命周期）。
 *
 * 下载/解压由应用级scope持有，离开工作区详情页（甚至退出设置）不会中断；
 * 页面只观察[states]展示进度，重新进入页面能接上正在跑的任务。
 * 这解决了把安装协程挂在rememberCoroutineScope上、离开页面即被取消的问题。
 */
object RootfsInstallManager {
    private const val TAG = "RootfsInstallManager"
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val starts = ConcurrentHashMap<String, Any>()
    private val _states = MutableStateFlow<Map<String, RootfsInstallState>>(emptyMap())

    /** workspaceId->当前安装状态（含终态，待UI消费） */
    val states: StateFlow<Map<String, RootfsInstallState>> = _states.asStateFlow()

    fun isRunning(workspaceId: String): Boolean = jobs[workspaceId]?.isActive == true

    /** 启动安装；同一工作区已有任务或url为空时返回false（不重复启动） */
    fun start(repository: WorkspaceRepository, workspaceId: String, url: String): Boolean {
        if (url.isBlank()) return false
        val gate = starts.computeIfAbsent(workspaceId) { Any() }
        synchronized(gate) {
            if (isRunning(workspaceId) || WorkspaceRuntimeCoordinator.isBlocked(workspaceId)) return false
            _states.update { it + (workspaceId to RootfsInstallState.Running(null)) }
            lateinit var job: Job
            job = appScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val ok = repository.installRootfs(workspaceId, url) { progress ->
                        _states.update { states ->
                            if (jobs[workspaceId] === job) states + (workspaceId to RootfsInstallState.Running(progress)) else states
                        }
                    }
                    _states.update { states ->
                        if (jobs[workspaceId] !== job) states
                        else states + (workspaceId to if (ok) RootfsInstallState.Success
                        else RootfsInstallState.Failure("Workspace not found: $workspaceId"))
                    }
                } catch (e: CancellationException) {
                    _states.update { states ->
                        if (jobs[workspaceId] === job) states + (workspaceId to RootfsInstallState.Cancelled) else states
                    }
                    throw e
                } catch (e: Throwable) {
                    DebugLog.e(TAG, "rootfs install failed: workspace=$workspaceId", e)
                    _states.update { states ->
                        if (jobs[workspaceId] === job) states + (workspaceId to RootfsInstallState.Failure(e.message ?: e.javaClass.simpleName)) else states
                    }
                } finally {
                    synchronized(gate) {
                        jobs.remove(workspaceId, job)
                        if (!jobs.containsKey(workspaceId)) starts.remove(workspaceId, gate)
                    }
                }
            }
            jobs[workspaceId] = job
            job.start()
            return true
        }
    }

    suspend fun cancelAndJoin(workspaceId: String) {
        val gate = starts.computeIfAbsent(workspaceId) { Any() }
        val job = synchronized(gate) { jobs[workspaceId]?.also { it.cancel() } } ?: return
        job.join()
    }

    /** UI消费完终态后清除（运行中的任务不受影响） */
    fun clearFinished(workspaceId: String) {
        if (isRunning(workspaceId)) return
        _states.update { it - workspaceId }
    }
}

package com.miniichatNext.carter.ui.workspace

import android.content.Context
import android.util.Log
import com.miniichatNext.carter.debug.DebugLog
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 拥有工作区终端会话，独立于终端页面生命周期（进程级单例）。
 * 页面只把TerminalView attach到选中的tab；离开页面shell与其屏幕缓冲保持存活，
 * tab仅在用户显式关闭（或shell自行退出）时结束。
 */
internal object WorkspaceTerminalSessionManager {
    private const val TAG = "WorkspaceTerminalManager"
    private lateinit var appContext: Context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()
    /** 各root当前tabs创建时使用的兼容模式（模式变化时需重建会话） */
    private val activeModes = mutableMapOf<String, Boolean>()

    /** 页面首次使用时注入应用上下文（重复调用无害） */
    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun ctx(): Context = appContext

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }

    internal fun ensureSession(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = true, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun createTab(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = false, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // 先从可观察状态移除再finish，避免选中TerminalView销毁时finish回调把它放回UI
        closedTab?.let { tab ->
            DebugLog.i(TAG, "tab closed: root=" + root + " tab=" + tabId)
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    /** 工作区rootfs被替换或删除前停止其全部会话 */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            DebugLog.i(TAG, "workspace closed, finishing tab: root=" + root + " tab=" + tab.id)
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    private fun launchCreateTab(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) {
        if (root in creationJobs) return

        lateinit var job: Job
        job = appScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                createTabInternal(root = root, onlyIfEmpty = onlyIfEmpty, shellCompatibilityMode = shellCompatibilityMode)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTabInternal(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating) return@withContext
        // 兼容模式变化：旧会话不会应用新模式，必须关闭重建（否则"开了开关没用"）
        val modeChanged = initialState.tabs.isNotEmpty() && activeModes[root] != shellCompatibilityMode
        if (modeChanged) {
            initialState.tabs.map { it.id }.forEach { id -> closeTab(root, id) }
            DebugLog.i(TAG, "compat mode changed, rebuilding sessions: root=" + root + " compat=" + shellCompatibilityMode)
        }
        if (onlyIfEmpty && !modeChanged && currentState(root).tabs.isNotEmpty()) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        // 每次创建tab都执行prepare（patch幂等且便宜）——不能因readiness==Ready短路，
        // 否则旧rootfs缓存会让passwd/shadow等补丁修复不生效
        val prepared = try {
            withContext(Dispatchers.IO) {
                if (!workspaceRootfsReady(ctx(), root)) {
                    false
                } else {
                    prepareWorkspaceTerminalSession(ctx(), root)
                    true
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DebugLog.e(TAG, "prepare failed: root=" + root, error)
            false
        }

        if (!prepared) {
            DebugLog.i(TAG, "terminal not ready: root=" + root)
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext
        }

        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(
            context = ctx(),
            onTitleUpdated = { title -> updateTitle(root, tabId, title) },
            onFinished = { markFinished(root = root, tabId = tabId) },
        )
        val session = runCatching {
            createWorkspaceTerminalSession(
                context = ctx(),
                root = root,
                client = client,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        }.onFailure { error ->
            DebugLog.e(TAG, "create failed: root=" + root, error)
        }.getOrNull()

        if (session == null) {
            DebugLog.e(TAG, "create returned null: root=" + root)
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }

        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
        activeModes[root] = shellCompatibilityMode
        DebugLog.i(TAG, "tab created: root=" + root + " tab=" + tabId + " number=" + tabNumber + " compat=" + shellCompatibilityMode + " total=" + currentState(root).tabs.size)
    }

    private fun updateTitle(root: String, tabId: Long, title: String?) {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(title = normalizedTitle) else tab
                },
            ))
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val title: String? = null,
    val finished: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}

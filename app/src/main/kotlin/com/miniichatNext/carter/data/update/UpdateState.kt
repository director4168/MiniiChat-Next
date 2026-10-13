package com.miniichatNext.carter.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 全局更新状态：
 * - [pending]：启动时auto-check或用户手动检查后，发现新版本则设置；UI在AppRoot监听并弹窗
 * - [ignoredVersions]：用户点"忽略此版本"时记录，下次auto-check跳过
 *
 * 注意：autoCheckEnabled仍持久化在SettingsRepository（用户在设置里开关），
 * 这里只暴露当前会话的运行态。
 */
object UpdateState {

    /** 等待展示的更新信息（null=没有新版本需要提示） */
    private val _pending = MutableStateFlow<UpdateInfo?>(null)
    val pending: StateFlow<UpdateInfo?> = _pending

    /** 用户已忽略的版本号集合（运行期，不持久化——持久化在SettingsRepository） */
    private val _ignoredVersions = MutableStateFlow<Set<String>>(emptySet())
    val ignoredVersions: StateFlow<Set<String>> = _ignoredVersions

    /**
     * 启动后台拉取+比对+设pending。
     * - 调用方传入[ignoredVersions]：被忽略的版本号集合
     * - 调用方传入[currentVersion]：当前versionName（nullable）
     * - 命中条件：远端version>currentVersion AND不在ignoredVersions里
     */
    fun tryShowUpdate(
        result: UpdateRepository.Result,
        currentVersion: String?,
        ignoredVersions: Set<String>,
    ) {
        if (result !is UpdateRepository.Result.Success) return
        val info = result.info
        if (info.version.isBlank()) return
        if (info.version in ignoredVersions) return
        val cur = currentVersion ?: "?"
        if (compareVersions(info.version, cur) <= 0) return // 不是新版本
        _pending.value = info
    }

    /**
     * 手动检查：忽略ignoredVersions列表——用户主动点"检测更新"就是想看现在能拉到啥。
     * 若被忽略的版本恰好就是最新版，就显示"已是最新版本"（由调用方Toast）。
     */
    fun forceShowUpdate(info: UpdateInfo) {
        _pending.value = info
    }

    fun clearPending() { _pending.value = null }

    fun addIgnoredVersion(version: String) {
        if (version.isBlank()) return
        _ignoredVersions.update { it + version }
    }

    fun clearIgnoredVersions() { _ignoredVersions.value = emptySet() }

    /**
     * 全局协程作用域（Application.onCreate用）。
     * SupervisorJob→单个失败不影响其他子任务。
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
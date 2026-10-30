package com.miniichatNext.carter

import android.app.Application
import com.miniichatNext.carter.debug.CrashLogger
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MiniiChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 调试日志等级持久化恢复、崩溃捕获
        DebugLog.restore(this)
        DebugLog.i("App", "MiniiChatApplication.onCreate")
        CrashLogger.install(this)
        val removedCacheEntries = com.miniichatNext.carter.util.TemporaryCacheCleaner.clearOnStartup(this)
        if (removedCacheEntries > 0) {
            DebugLog.i("Cache", "cleared $removedCacheEntries temporary cache entrie(s)")
        }
        // 前后台追踪（更新下载完成后决定是否可拉起安装器）
        registerActivityLifecycleCallbacks(com.miniichatNext.carter.util.AppForeground.callbacks)
        // 不在每次启动清缓存——用户已下载但未安装的APK要保留。
        // 清缓存时机改为：新下载前（UpdateDownloader.start删旧包）/用户主动取消。
        // 启动自动检查更新（用户设置关闭则跳过）
        kickOffAutoUpdateCheck()
        // 恢复日志等级悬浮球（开关开启且"显示在其他应用上层"权限已授予）
        com.miniichatNext.carter.data.update.UpdateState.scope.launch {
            runCatching {
                val settings = com.miniichatNext.carter.data.store.SettingsRepository(this@MiniiChatApplication)
                    .settings.first()
                if (settings.debugLogBall) {
                    com.miniichatNext.carter.debug.DebugLogBallService.start(this@MiniiChatApplication)
                }
            }
        }
    }

    private fun kickOffAutoUpdateCheck() {
        com.miniichatNext.carter.data.update.UpdateState.scope.launch {
            try {
                val (enabled, ignored) = com.miniichatNext.carter.data.store.SettingsRepository(this@MiniiChatApplication)
                    .snapshotForUpdate()
                if (!enabled) {
                    DebugLog.i("Update", "auto-check disabled, skip")
                    return@launch
                }
                val localCode = com.miniichatNext.carter.data.update.localVersionCode(this@MiniiChatApplication)
                val result = com.miniichatNext.carter.data.update.UpdateRepository.checkForUpdate(this@MiniiChatApplication)
                DebugLog.i("Update", "auto-check: ${result::class.simpleName}")
                com.miniichatNext.carter.data.update.UpdateState.tryShowUpdate(
                    result = result,
                    localCode = localCode,
                    ignoredVersions = ignored,
                )
            } catch (t: Throwable) {
                DebugLog.e("Update", "auto-check failed", t)
            }
        }
    }
}

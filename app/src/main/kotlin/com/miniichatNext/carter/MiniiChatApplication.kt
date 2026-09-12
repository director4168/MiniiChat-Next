package com.miniichatNext.carter

import android.app.Application
import com.miniichatNext.carter.Debug.CrashLogger
import com.miniichatNext.carter.Debug.DebugLog

class MiniiChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 调试日志等级持久化恢复 + 崩溃捕获（写入 filesDir/crash/latest_crash.log）
        DebugLog.restore(this)
        DebugLog.i("App", "MiniiChatApplication.onCreate")
        CrashLogger.install(this)
    }
}

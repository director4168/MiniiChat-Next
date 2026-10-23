package com.miniichatNext.carter.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 应用前后台状态追踪（ActivityLifecycleCallbacks计数，零依赖）。
 * 用途：更新下载完成后判断是否可拉起安装器
 * Android 10+后台启动Activity会被系统静默拦截（startActivity不抛异常但界面不出现），
 * 后台完成时改为"待安装"状态，回前台由用户点"立即安装"。
 */
object AppForeground {
    @Volatile
    private var started = 0

    fun isForeground(): Boolean = started > 0

    val callbacks: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}

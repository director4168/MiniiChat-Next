/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.debug

import android.app.ActivityManager
import android.content.Context

internal object DebugDataWipe {
    fun clear(context: Context): Boolean {
        return runCatching {
            context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
        }.getOrDefault(false)
    }
}

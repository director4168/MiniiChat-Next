package com.miniichatNext.carter.data.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同一进程只记录首次进入应用，Activity重建和引导页往返不重复累计。 */
class AppLaunchRecorder(private val settings: SettingsRepository) {
    private val mutex = Mutex()
    private var recorded = false

    suspend fun recordOnce() = mutex.withLock {
        if (recorded) return@withLock
        settings.update { current ->
            current.copy(launchCount = if (current.launchCount == Long.MAX_VALUE) Long.MAX_VALUE else current.launchCount.coerceAtLeast(0L) + 1L)
        }
        recorded = true
    }
}

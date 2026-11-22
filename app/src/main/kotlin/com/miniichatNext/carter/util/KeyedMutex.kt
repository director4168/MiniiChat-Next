/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class KeyedMutex<K> {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<K, Entry>()

    suspend fun <T> withKey(key: K, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(key)
            }
        }
    }
}

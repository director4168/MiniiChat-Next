package com.miniichatNext.carter.data.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object WorkspaceRuntimeCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lifecycleLocks = ConcurrentHashMap<String, Mutex>()
    private val blocked = ConcurrentHashMap<String, AtomicInteger>()

    fun block(root: String) { blocked.computeIfAbsent(root) { AtomicInteger() }.incrementAndGet() }
    fun unblock(root: String) {
        blocked[root]?.let { count -> if (count.decrementAndGet() <= 0) blocked.remove(root, count) }
    }
    fun isBlocked(root: String) = (blocked[root]?.get() ?: 0) > 0

    suspend fun <T> withLifecycle(root: String, block: suspend () -> T): T =
        lifecycleLocks.computeIfAbsent(root) { Mutex() }.withLock { block() }

    suspend fun <T> withWorkspace(root: String, allowBlocked: Boolean = false, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(root) { Mutex() }
        return mutex.withLock {
            check(allowBlocked || !isBlocked(root)) { "Workspace is changing: $root" }
            block()
        }
    }
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.workspace

import com.miniichatNext.carter.util.KeyedMutex
import java.util.concurrent.ConcurrentHashMap

internal object WorkspaceRuntimeCoordinator {
    private val locks = KeyedMutex<String>()
    private val lifecycleLocks = KeyedMutex<String>()
    private val blocked = ConcurrentHashMap<String, Int>()

    fun block(root: String) { blocked.merge(root, 1, Int::plus) }
    fun unblock(root: String) { blocked.computeIfPresent(root) { _, count -> (count - 1).takeIf { it > 0 } } }
    fun isBlocked(root: String) = blocked.containsKey(root)

    suspend fun <T> withLifecycle(root: String, block: suspend () -> T): T = lifecycleLocks.withKey(root, block)

    suspend fun <T> withWorkspace(root: String, allowBlocked: Boolean = false, block: suspend () -> T): T =
        locks.withKey(root) {
            check(allowBlocked || !isBlocked(root)) { "Workspace is changing: $root" }
            block()
        }
}

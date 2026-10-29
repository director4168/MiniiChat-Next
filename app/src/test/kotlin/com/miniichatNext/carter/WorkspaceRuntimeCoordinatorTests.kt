package com.miniichatNext.carter

import com.miniichatNext.carter.data.workspace.WorkspaceRuntimeCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRuntimeCoordinatorTests {
    @Test fun differentWorkspacesCanProceedIndependently() = runBlocking {
        val a = WorkspaceRuntimeCoordinator.withWorkspace("a") { "a" }
        val b = WorkspaceRuntimeCoordinator.withWorkspace("b") { "b" }
        assertTrue(a == "a" && b == "b")
    }

    @Test fun lifecycleBlockRejectsNewShellOperation() = runBlocking {
        WorkspaceRuntimeCoordinator.block("root")
        try {
            assertTrue(WorkspaceRuntimeCoordinator.isBlocked("root"))
            val result = runCatching { WorkspaceRuntimeCoordinator.withWorkspace("root") { "ran" } }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("changing"))
            assertTrue(WorkspaceRuntimeCoordinator.withWorkspace("root", allowBlocked = true) { "cleanup" } == "cleanup")
        } finally { WorkspaceRuntimeCoordinator.unblock("root") }
        assertFalse(WorkspaceRuntimeCoordinator.isBlocked("root"))
    }

    @Test fun lifecycleWaitsForActiveShellCommandBeforeRootMutation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val shell = async { WorkspaceRuntimeCoordinator.withWorkspace("root") { entered.complete(Unit); release.await() } }
        withTimeout(2_000) { entered.await() }
        WorkspaceRuntimeCoordinator.block("root")
        val lifecycle = async { WorkspaceRuntimeCoordinator.withWorkspace("root", allowBlocked = true) { "mutated" } }
        delay(40)
        assertFalse(lifecycle.isCompleted)
        release.complete(Unit)
        assertTrue(lifecycle.await() == "mutated")
        shell.await()
        WorkspaceRuntimeCoordinator.unblock("root")
    }
}

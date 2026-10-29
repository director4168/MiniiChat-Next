package com.miniichatNext.carter

import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.data.mcp.resolveToolPermission
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.mergeCompressedMessages
import com.miniichatNext.carter.data.workspace.MAX_OUTPUT_CHARS
import com.miniichatNext.carter.data.workspace.RootfsPatcher
import com.miniichatNext.carter.data.workspace.readResult
import com.miniichatNext.carter.vm.ChatTaskSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class AuditBatchOneTests {
    private fun message(id: String, content: String = id) = Message(id, "user", content)

    @Test
    fun compressionPreservesNewMessagesAndLatestTail() {
        val first = message("first")
        val tail = message("tail", "edited while waiting")
        val appended = message("new")
        val summary = message("summary")
        assertEquals(listOf(summary, tail, appended),
            mergeCompressedMessages(listOf(first, tail, appended), listOf(first), summary))
    }

    @Test
    fun compressionDoesNotApplyToEditedOrDeletedPrefix() {
        val source = listOf(message("first"), message("second"))
        assertNull(mergeCompressedMessages(listOf(message("first", "changed"), source[1]), source, message("summary")))
        assertNull(mergeCompressedMessages(source.drop(1), source, message("summary")))
        assertNull(mergeCompressedMessages(source, emptyList(), message("summary")))
    }

    @Test
    fun workspaceCategoryOverridesOldGlobalApproval() {
        val old = McpToolPermission("workspace_shell", "workspace", requireApproval = true)
        val current = resolveToolPermission("workspace_shell", "workspace", listOf(old), true, categoryApproval = false)
        assertTrue(current.enabled)
        assertFalse(current.requireApproval)
    }

    @Test
    fun permissionsMatchServerAndKeepDisabledState() {
        val permissions = listOf(
            McpToolPermission("same", "first", requireApproval = true),
            McpToolPermission("same", "second", enabled = false, requireApproval = false),
        )
        assertFalse(resolveToolPermission("same", "third", permissions, false).requireApproval)
        val second = resolveToolPermission("same", "second", permissions, true)
        assertFalse(second.enabled)
        assertFalse(second.requireApproval)
    }

    @Test
    fun cancellingSessionCancelsApprovedToolAndPreventsContinuation() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ChatTaskSession("conversation", parent)
        val started = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val completed = AtomicInteger()
        try {
            session.launchTool("call") {
                started.complete(Unit)
                try {
                    delay(60_000)
                    completed.incrementAndGet()
                } finally {
                    ended.complete(Unit)
                }
            }
            withTimeout(5_000) { started.await() }
            session.cancel()
            withTimeout(5_000) { ended.await() }
            assertEquals(0, completed.get())
            assertFalse(session.claimContinuation("message:batch"))
        } finally { parent.cancel() }
    }

    @Test
    fun repeatedApprovalStartsOnlyOneToolTask() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ChatTaskSession("conversation", parent)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            repeat(100) {
                session.launchTool("same-call") {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                }
            }
            withTimeout(5_000) { started.await() }
            assertEquals(1, calls.get())
        } finally { session.cancel(); parent.cancel() }
    }

    @Test
    fun continuationCanBeClaimedOncePerBatch() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ChatTaskSession("conversation", parent)
        try {
            assertTrue(session.claimContinuation("message:first"))
            assertFalse(session.claimContinuation("message:first"))
            assertTrue(session.claimContinuation("message:second"))
        } finally { session.cancel(); parent.cancel() }
    }

    @Test
    fun toolWaitsUntilGeneratingRoundFinishes() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ChatTaskSession("conversation", parent)
        val streamGate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val toolDone = CompletableDeferred<Unit>()
        try {
            session.launchGeneration { started.complete(Unit); streamGate.await() }
            withTimeout(5_000) { started.await() }
            session.launchTool("call") { toolDone.complete(Unit) }
            delay(50)
            assertFalse(toolDone.isCompleted)
            streamGate.complete(Unit)
            withTimeout(5_000) { toolDone.await() }
        } finally { session.cancel(); parent.cancel() }
    }

    @Test
    fun replacedGenerationHasDifferentOwnershipVersion() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ChatTaskSession("conversation", parent)
        val started = CompletableDeferred<Long>()
        val cleanup = CompletableDeferred<Boolean>()
        try {
            val first = session.launchGeneration { version ->
                started.complete(version)
                try { delay(60_000) } finally {
                    cleanup.complete(session.generationVersion == version)
                }
            }
            val version = withTimeout(5_000) { started.await() }
            session.launchGeneration { delay(60_000) }
            withTimeout(5_000) { first.join() }
            assertTrue(session.generationVersion > version)
            assertFalse(cleanup.await())
        } finally { session.cancel(); parent.cancel() }
    }

    @Test
    fun largeShellOutputIsDrainedAndBounded() {
        val process = ProcessBuilder("sh", "-c", "head -c 1048576 /dev/zero").start()
        val result = process.readResult(10_000)
        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
    }

    @Test
    fun shellWithoutInputReceivesEof() {
        val process = ProcessBuilder("sh", "-c", "cat >/dev/null; printf done").start()
        val result = process.readResult(5_000)
        assertFalse(result.timedOut)
        assertEquals("done", result.stdout)
    }

    @Test
    fun shellCancellationStopsProcess() = runBlocking {
        val process = ProcessBuilder("sh", "-c", "exec sleep 60").start()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val started = CompletableDeferred<Unit>()
            val job = parent.launch {
                runInterruptible {
                    started.complete(Unit)
                    process.readResult(60_000)
                }
            }
            withTimeout(5_000) { started.await() }
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertFalse(process.isAlive)
        } finally { process.destroyForcibly(); parent.cancel() }
    }

    @Test
    fun patchingRootfsPreservesLockedAptFiles() {
        val root = Files.createTempDirectory("apt-lock-test").toFile()
        try {
            File(root, "etc").mkdirs()
            val lock = File(root, "var/lib/dpkg/lock").apply { parentFile?.mkdirs(); writeText("") }
            val partial = File(root, "var/lib/apt/lists/partial/data").apply { parentFile?.mkdirs(); writeText("active") }
            RandomAccessFile(lock, "rw").use { file ->
                val held = file.channel.lock()
                try {
                    RootfsPatcher().patch(root)
                    assertTrue(held.isValid)
                    assertTrue(lock.exists())
                    assertEquals("active", partial.readText())
                } finally { held.release() }
            }
        } finally { root.deleteRecursively() }
    }
}

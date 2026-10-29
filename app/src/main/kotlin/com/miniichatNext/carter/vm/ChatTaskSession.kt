package com.miniichatNext.carter.vm

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一轮对话的生成和审批任务共用父Job，取消后旧回调失效。 */
internal class ChatTaskSession(
    val conversationId: String,
    parent: CoroutineScope,
) {
    private val root: CompletableJob = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + root)
    private val toolJobs = ConcurrentHashMap<String, Job>()
    private val resumedBatches = ConcurrentHashMap.newKeySet<String>()
    private val calls = ConcurrentHashMap.newKeySet<String>()
    private val versionCounter = AtomicLong(0L)
    val generationVersion: Long get() = versionCounter.get()
    @Volatile
    var generation: Job? = null
        private set
    val isActive: Boolean get() = root.isActive

    fun rememberTool(callId: String) { calls.add(callId) }
    fun toolCallIds(): Set<String> = calls.toSet()
    fun cancel() { root.cancel() }

    @Synchronized
    fun launchGeneration(block: suspend (Long) -> Unit): Job {
        check(isActive)
        val version = versionCounter.incrementAndGet()
        generation?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { block(version) }
        generation = job
        job.start()
        return job
    }

    @Synchronized
    fun launchTool(callId: String, block: suspend () -> Unit) {
        if (!isActive || toolJobs[callId]?.isActive == true) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                generation?.join()
                block()
            } finally {
                toolJobs.remove(callId, job)
            }
        }
        toolJobs[callId] = job
        job.start()
    }

    fun claimContinuation(batchKey: String): Boolean = isActive && resumedBatches.add(batchKey)
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.model

import com.miniichatNext.carter.api.StreamDelta
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** 移植rikkahub UsageRecorder：同请求merge，结束或取消时只记录一次。 */
internal fun Flow<StreamDelta>.recordUsage(record: suspend (TokenUsage) -> Unit): Flow<StreamDelta> = flow {
    var usage: TokenUsage? = null
    try {
        collect { chunk ->
            chunk.usage?.let { usage = usage.merge(it) }
            emit(chunk)
        }
    } finally {
        usage?.let { total ->
            withContext(NonCancellable) { record(total) }
        }
    }
}

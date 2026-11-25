/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ExternalGgufRequests {
    class Request(val uri: Uri)

    private val pending = MutableStateFlow<Request?>(null)
    val current = pending.asStateFlow()

    fun offer(uri: Uri?) {
        if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            pending.value = Request(uri)
        }
    }

    fun consume(request: Request): Boolean = pending.compareAndSet(request, null)
}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.tools

import com.miniichatNext.carter.data.workspace.readResult

/** 在Shizuku的用户服务进程里执行命令；进程UID由Shizuku授予。 */
class ShizukuShellService : IShizukuShellService.Stub() {
    override fun execute(script: String, timeoutSeconds: Int): String {
        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(false)
            .start()
        val result = process.readResult(timeoutSeconds.coerceIn(1, 600) * 1000L)
        return "${result.exitCode}\u0001${result.stdout}\u0001${result.stderr}"
    }
}

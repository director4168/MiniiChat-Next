/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.tools

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.miniichatNext.carter.data.workspace.WorkspaceCommandResult
import com.miniichatNext.carter.data.workspace.readResult
import com.miniichatNext.carter.debug.DebugLog
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 本机高级Shell：Shizuku或root。所有调用方都必须放到IO线程。 */
object ShellAccess {
    const val SHIZUKU_REQUEST_CODE = 0x5348

    @Volatile
    private var shizukuService: IShizukuShellService? = null
    private val bindLock = Any()

    fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isShizukuGranted(): Boolean =
        isShizukuAvailable() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestShizukuPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return runCatching {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    fun isRootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val result = process.readResult(3_000)
        result.exitCode == 0 && !result.timedOut && result.stdout.contains("uid=0")
    }.getOrDefault(false)

    fun executeShizuku(context: Context, script: String, timeoutSeconds: Long): WorkspaceCommandResult {
        val service = bindShizuku(context) ?: error("Shizuku service is unavailable")
        val parts = service.execute(script, timeoutSeconds.coerceIn(1, 600).toInt()).split('\u0001')
        val exitCode = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val stdout = parts.getOrNull(1).orEmpty()
        val stderr = parts.getOrNull(2).orEmpty()
        return WorkspaceCommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            timedOut = false,
            truncated = false,
        )
    }

    fun executeRoot(script: String, timeoutSeconds: Long): WorkspaceCommandResult =
        ProcessBuilder("su", "-c", script)
            .redirectErrorStream(false)
            .start()
            .readResult(TimeUnit.SECONDS.toMillis(timeoutSeconds))

    private fun bindShizuku(context: Context): IShizukuShellService? {
        shizukuService?.let { return it }
        if (!isShizukuGranted()) return null
        return synchronized(bindLock) {
            shizukuService?.let { return it }
            val latch = CountDownLatch(1)
            var connected: IShizukuShellService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    connected = IShizukuShellService.Stub.asInterface(binder)
                    shizukuService = connected
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shizukuService = null
                    latch.countDown()
                }
            }
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShizukuShellService::class.java.name)
            ).daemon(false).processNameSuffix("shell").debuggable(false).version(1)
            val bindCall = Runnable {
                runCatching { Shizuku.bindUserService(args, connection) }
                    .onFailure {
                        DebugLog.e("ShellAccess", "bind Shizuku user service failed", it)
                        latch.countDown()
                    }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) bindCall.run()
            else Handler(Looper.getMainLooper()).post(bindCall)
            latch.await(10, TimeUnit.SECONDS)
            connected
        }
    }
}

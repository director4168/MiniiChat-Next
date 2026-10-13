package com.miniichatNext.carter.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.miniichatNext.carter.data.workspace.RootfsPatcher
import com.miniichatNext.carter.data.workspace.WorkspaceManager
import com.miniichatNext.carter.data.workspace.WorkspaceRepository
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

private const val TERMINAL_WORKSPACE_DIR = "/workspace"
private const val TERMINAL_SKILLS_DIR = "/skills"

/** 交互终端命令行：与ProotShellRunner.buildCommand同源（bind mounts/kernel FS/env），交互shell=/bin/bash -l */
internal fun buildWorkspaceTerminalCommand(
    context: Context,
    root: String,
): Triple<File, File, List<String>> {
    val appContext = context.applicationContext
    val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot_exec.so")
    val workspaceDir = WorkspaceRepository.defaultManager(context).workspaceDir(root)
    val filesDir = WorkspaceRepository.defaultManager(context).filesDir(root)
    val linuxDir = WorkspaceRepository.defaultManager(context).linuxDir(root)
    val skillsDir = File(appContext.filesDir, "skills")

    val args = mutableListOf(
        "--root-id",
        "--link2symlink",
        "--kill-on-exit",
        "-r",
        linuxDir.absolutePath,
        "-w",
        TERMINAL_WORKSPACE_DIR,
        "-b",
        filesDir.absolutePath + ":" + TERMINAL_WORKSPACE_DIR,
    )
    if (skillsDir.exists()) {
        args += "-b"
        args += skillsDir.absolutePath + ":" + TERMINAL_SKILLS_DIR
    }
    listOf("/dev", "/proc", "/sys").forEach { path ->
        if (File(path).exists()) {
            args += "-b"
            args += path
        }
    }
    val shell = WorkspaceManager.rootfsShell(linuxDir)
    args += listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=" + shell,
        shell,
        "-l",
    )
    return Triple(proot, filesDir, args)
}

/** 终端启动前准备：目录与rootfs补丁（DNS与WorkspaceShellRunner同源） */
internal fun prepareWorkspaceTerminalSession(context: Context, root: String) {
    val manager = WorkspaceRepository.defaultManager(context)
    manager.filesDir(root).mkdirs()
    manager.tempDir(root).mkdirs()
    val dnsServers = context.activeDnsServers()
    RootfsPatcher().patch(
        manager.linuxDir(root),
        com.miniichatNext.carter.data.workspace.RootfsPatchOptions(nameservers = dnsServers),
    )
}

internal fun workspaceRootfsReady(context: Context, root: String): Boolean =
    WorkspaceRepository.defaultManager(context).hasRootfs(root)

private fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    return connectivityManager.activeNetwork
        ?.let { connectivityManager.getLinkProperties(it) }
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}

/** 会话创建：TerminalSession内部通过JNI fork+ptmx提供真PTY */
internal fun createWorkspaceTerminalSession(
    context: Context,
    root: String,
    client: TerminalSessionClient,
    shellCompatibilityMode: Boolean = false,
): TerminalSession {
    val (proot, cwd, args) = buildWorkspaceTerminalCommand(context, root)
    val appContext = context.applicationContext
    val env = buildList {
        add("PROOT_LOADER=" + File(appContext.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath)
        add("PROOT_TMP_DIR=" + WorkspaceRepository.defaultManager(context).tempDir(root).absolutePath)
        add("TMPDIR=" + WorkspaceRepository.defaultManager(context).tempDir(root).absolutePath)
        if (shellCompatibilityMode) add("PROOT_NO_SECCOMP=1")
    }
    val session = TerminalSession(
        proot.absolutePath,
        cwd.absolutePath,
        args.toTypedArray(),
        env.toTypedArray(),
        2_000,
        client,
    )
    session.mSessionName = root
    return session
}

/** TerminalSessionClient：emulator事件转发到TerminalView与剪贴板 */
internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onTitleUpdated: (String?) -> Unit,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleUpdated(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

/** TerminalViewClient：缩放范围/单击聚焦键盘（v1轻量） */
internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null

    /** CTRL/ALT锁定状态（ExtraKeysBar切换；TerminalView发送按键前经readControlKey/readAltKey读取） */
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        focusAndShowKeyboard()
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    // false→inputType=TYPE_NULL：Gboard等走sendKeyEvent（带metaState），
    // CTRL/ALT组合键才可用；true(password模式)下IME走commitText，Ctrl会失效
    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    private var ctrlLogged = false

    override fun readControlKey(): Boolean {
        if (controlDown && !ctrlLogged) {
            ctrlLogged = true
            com.miniichatNext.carter.debug.DebugLog.i("Terminal", "readControlKey()=true (ctrl active)")
        }
        return controlDown
    }

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}

/** 便捷写文本（extra keys用） */
internal fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}

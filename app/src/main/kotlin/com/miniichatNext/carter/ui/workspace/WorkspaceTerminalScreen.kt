package com.miniichatNext.carter.ui.workspace

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.miniichatNext.carter.R
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.ui.navigation.AppBackDispatcher
import com.termux.view.TerminalView
import android.graphics.Typeface as MonoTypeface

/**
 * 工作区交互式终端（rikkahub WorkspaceTerminalPage移植）：
 * 真PTY（termux terminal-emulator）+ANSI渲染；多tab进程保活。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WorkspaceTerminalScreen(
    workspaceId: String,
    workspaceRoot: String?,
    workspaceName: String,
    onBack: () -> Unit,
    backDispatcher: AppBackDispatcher? = null,
    shellCompatibilityMode: Boolean = false,
) {
    val context = LocalContext.current
    val root = workspaceRoot

    var pendingCloseTabId by remember { mutableStateOf<Long?>(null) }
    var sessionTitle by remember { mutableStateOf<String?>(null) }

    // 会话状态：manager为进程级单例，页面离开后shell与屏幕缓冲保持存活
    val terminalState = remember { WorkspaceTerminalSessionManager.observeWorkspace(root.orEmpty()) }
    val state by terminalState.collectAsState(initial = WorkspaceTerminalTabsState())

    LaunchedEffect(root) {
        if (root != null) {
            WorkspaceTerminalSessionManager.init(context)
            WorkspaceTerminalSessionManager.ensureSession(root, shellCompatibilityMode)
        }
    }

    // 终端整页强制深色，但主色跟随全局主题（darkColorScheme默认紫，避免终端控件紫蓝混用）
    val outerScheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = outerScheme.primary,
            onPrimary = outerScheme.onPrimary,
            primaryContainer = outerScheme.primaryContainer,
            onPrimaryContainer = outerScheme.onPrimaryContainer,
            background = Color.Black,
            surface = Color.Black,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            ) {
                SettingsTopBar(
                    title = sessionTitle
                        ?: workspaceName.ifBlank { stringResource(R.string.workspace_terminal_title) },
                    onBack = onBack,
                )

                // tab行：每tab一个chip（编号+关闭×），末尾新建按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { tab ->
                        val selected = tab.id == state.selectedTabId
                        Row(
                            modifier = Modifier
                                .clickable { WorkspaceTerminalSessionManager.selectTab(root.orEmpty(), tab.id) }
                                .background(
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .widthIn(min = 90.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.workspace_terminal_tab, tab.number),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "×",
                                modifier = Modifier
                                    .clickable { pendingCloseTabId = tab.id }
                                    .padding(start = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "+",
                        modifier = Modifier
                            .clickable {
                                if (root != null) {
                                    WorkspaceTerminalSessionManager.createTab(root, shellCompatibilityMode)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
                // finished不替换内容：保留终端最后画面可滚动查看，只是不能再输入
                if (selectedTab == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.readiness == WorkspaceTerminalReadiness.NotInstalled) {
                                stringResource(R.string.workspace_terminal_not_installed)
                            } else {
                                stringResource(R.string.workspace_terminal_loading)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    TerminalTabContent(
                        tab = selectedTab,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onTitleChanged = { sessionTitle = it },
                    )
                }
            }

            val pendingClose = state.tabs.firstOrNull { it.id == pendingCloseTabId }
            if (pendingClose != null) {
                AlertDialog(
                    onDismissRequest = { pendingCloseTabId = null },
                    title = {
                        Text(stringResource(R.string.workspace_terminal_close_confirm_title, pendingClose.number))
                    },
                    text = { Text(stringResource(R.string.workspace_terminal_close_confirm_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            WorkspaceTerminalSessionManager.closeTab(root.orEmpty(), pendingClose.id)
                            pendingCloseTabId = null
                        }) { Text(stringResource(R.string.workspace_terminal_close)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingCloseTabId = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }
}

/** 单个tab的终端视图（AndroidView包TerminalView；离开页面仅解绑view，会话保活） */
@Composable
private fun TerminalTabContent(
    tab: WorkspaceTerminalTab,
    modifier: Modifier = Modifier,
    onTitleChanged: (String?) -> Unit,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    val terminalTypeface = MonoTypeface.MONOSPACE

    var controlDown by remember(tab.id) { mutableStateOf(false) }
    var altDown by remember(tab.id) { mutableStateOf(false) }
    val viewClient = remember(tab.id) {
        WorkspaceTerminalViewClient(context)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown
    DisposableEffect(tab.id, viewClient) {
        onDispose {
            if (tab.client.terminalView === viewClient.terminalView) {
                tab.client.terminalView = null
            }
            viewClient.terminalView = null
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    TerminalView(viewContext, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(terminalTextSizePx)
                        setTypeface(terminalTypeface)
                        setTerminalViewClient(viewClient)
                        attachSession(tab.session)
                        tab.client.terminalView = this
                        viewClient.terminalView = this
                        post { viewClient.focusAndShowKeyboard() }
                    }
                },
                update = { terminalView ->
                    terminalView.setTextSize(terminalTextSizePx)
                    terminalView.setTypeface(terminalTypeface)
                    terminalView.setTerminalViewClient(viewClient)
                    tab.client.terminalView = terminalView
                    viewClient.terminalView = terminalView
                    terminalView.attachSession(tab.session)
                    terminalView.onScreenUpdated()
                },
            )
            if (tab.finished) {
                Text(
                    text = stringResource(R.string.workspace_terminal_exited),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        TerminalExtraKeysBar(
            controlDown = controlDown,
            altDown = altDown,
            onControlToggle = {
                            controlDown = !controlDown
                            com.miniichatNext.carter.debug.DebugLog.i("Terminal", "CTRL toggled: " + controlDown)
                        },
            onAltToggle = { altDown = !altDown },
            onSendText = { tab.session.writeText(it) },
        )
    }
}

/** 终端外接键行（ESC/TAB/CTRL/ALT/方向/HOME/END） */
@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        },
    )
}

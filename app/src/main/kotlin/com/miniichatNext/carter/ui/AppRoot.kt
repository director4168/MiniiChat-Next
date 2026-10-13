package com.miniichatNext.carter.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.miniichatNext.carter.vm.ChatViewModel
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.debug.CrashLogger
import com.miniichatNext.carter.debug.ui.CrashReportScreen
import com.miniichatNext.carter.debug.ui.DebugScreen
import com.miniichatNext.carter.debug.ui.DialogTestScreen
import kotlinx.coroutines.launch
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.debug.DebugLevel
import androidx.compose.foundation.clickable
import com.miniichatNext.carter.ui.assistant.AssistantEditorScreen
import com.miniichatNext.carter.ui.assistant.AssistantsScreen
import com.miniichatNext.carter.ui.chat.ChatScreen
import com.miniichatNext.carter.ui.chat.CompressContextDialog
import com.miniichatNext.carter.ui.chat.ModelPickerSheet
import com.miniichatNext.carter.ui.components.ToastStackHost
import com.miniichatNext.carter.data.mcp.McpClient
import com.miniichatNext.carter.ui.mcp.BuiltInToolsScreen
import com.miniichatNext.carter.ui.mcp.McpServerDetailScreen
import com.miniichatNext.carter.ui.mcp.McpServerEditorScreen
import com.miniichatNext.carter.ui.mcp.McpServersScreen
import com.miniichatNext.carter.ui.navigation.AppBackDispatcher
import com.miniichatNext.carter.ui.navigation.AppNavHost
import com.miniichatNext.carter.ui.navigation.NavController
import com.miniichatNext.carter.ui.provider.ModelEditScreen
import com.miniichatNext.carter.ui.provider.ProviderEditorScreen
import com.miniichatNext.carter.ui.provider.ProvidersScreen
import com.miniichatNext.carter.ui.settings.AboutScreen
import com.miniichatNext.carter.ui.settings.SettingsScreen
import com.miniichatNext.carter.ui.settings.UserProfileScreen
import com.miniichatNext.carter.ui.skills.SkillsScreen
import com.miniichatNext.carter.ui.workspace.WorkspaceDetailScreen
import com.miniichatNext.carter.ui.workspace.WorkspaceListScreen
import com.miniichatNext.carter.data.model.ProviderOverride
import com.miniichatNext.carter.vm.addManualModel
import com.miniichatNext.carter.vm.approveToolCall
import com.miniichatNext.carter.vm.clearConversationToolOverrides
import com.miniichatNext.carter.vm.compressContext
import com.miniichatNext.carter.vm.continueGenerating
import com.miniichatNext.carter.vm.deleteAssistant
import com.miniichatNext.carter.vm.deleteMessage
import com.miniichatNext.carter.vm.deleteProvider
import com.miniichatNext.carter.vm.deleteSkill
import com.miniichatNext.carter.vm.editMessage
import com.miniichatNext.carter.vm.effectiveMcpServerIds
import com.miniichatNext.carter.vm.effectiveSkillIds
import com.miniichatNext.carter.vm.effectiveSkillIdsFlow
import com.miniichatNext.carter.vm.effectiveWorkspaceId
import com.miniichatNext.carter.vm.ensureActiveConversationId
import com.miniichatNext.carter.vm.fetchModels
import com.miniichatNext.carter.vm.generateSuggestions
import com.miniichatNext.carter.vm.rejectToolCall
import com.miniichatNext.carter.vm.providerOverride
import com.miniichatNext.carter.vm.setConversationProviderOverride
import com.miniichatNext.carter.vm.setConversationThinkingLevel
import com.miniichatNext.carter.vm.regenerateFrom
import com.miniichatNext.carter.vm.removeModel
import com.miniichatNext.carter.vm.saveSkill
import com.miniichatNext.carter.vm.saveSkillFiles
import com.miniichatNext.carter.vm.selectAssistant
import com.miniichatNext.carter.vm.selectModel
import com.miniichatNext.carter.vm.sendMessage
import com.miniichatNext.carter.vm.setConversationMcpServer
import com.miniichatNext.carter.vm.setConversationWorkspace
import com.miniichatNext.carter.vm.setSkillEnabled
import com.miniichatNext.carter.vm.toggleAssistantSkill
import com.miniichatNext.carter.vm.toggleTemporarySkill
import com.miniichatNext.carter.vm.updateModelConfig
import com.miniichatNext.carter.vm.updateUserProfile
import com.miniichatNext.carter.vm.upsertAssistant
import com.miniichatNext.carter.vm.upsertProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.widget.Toast

/** 路由表：新增页面只需在这里加一项（配合AppNavRegistry可自定义过渡动画） */
internal enum class Screen {
    Chat, Settings, Providers, ProviderEdit, ModelEdit, Assistants, AssistantEdit,
    Skills, UserProfile, About, Debug, DialogTest, ThemeColors, UpdateSettings, Acknowledgements,
    Workspace, WorkspaceDetail, WorkspaceTerminal,
    Mcp, McpDetail, McpEdit, McpBuiltIn,
    MermaidPreview
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val mcpServers by vm.mcpServers.collectAsState()
    // 对外可见的MCP服务器：设置里关闭的服务器在助手/聊天等处完全隐藏（仅MCP设置列表保留）；
    // 编辑页（initial查找）仍用全量——已关闭的服务器也要能进编辑
    val visibleMcpServers = mcpServers.filter { it.enabled }
    val workspaces by vm.workspaces.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 统一导航：返回栈+过渡动画（动画可在AppNavRegistry里按路由定制）
    val nav = rememberSaveable(saver = NavController.Saver) { NavController(Screen.Chat.name) }
    // 统一返回调度：页面可注册层级内返回（如MCP详情→列表），未消费时才弹栈
    val backDispatcher = remember { AppBackDispatcher() }
    // 后期自定义示例：给单个路由换一套动画
    // AppNavRegistry.registerRouteTransition(Screen.Debug.name, NavTransitionPresets.Scale.forward)
    // 后期自定义示例：注册全局返回兜底（比如“再按一次退出”）
    // backDispatcher.fallback = { ... ; true }

    LaunchedEffect(nav.current.route) {
        DebugLog.v("Nav", "route -> ${nav.current.route} (depth=${nav.current.depth})")
    }

    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingModelId by remember { mutableStateOf<String?>(null) }
    var editingAssistant by remember { mutableStateOf<Assistant?>(null) }
    // 启动时auto-check触发的更新弹窗：监听全局UpdateState.pending，
    // 任意页面都能弹出（不依赖用户在AboutScreen里）。
    val pendingUpdate by com.miniichatNext.carter.data.update.UpdateState.pending.collectAsState()
    val currentVersionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val settingsRepo = remember { com.miniichatNext.carter.data.store.SettingsRepository(context) }
    // 把Settings滚动状态提升到AppRoot（用rememberSaveable持久化），导航到关于页再返回时滚动位置不会跳到顶部
    val settingsScrollState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = ScrollState.Saver
    ) { ScrollState(0) }
    // 滚动位置恢复策略：只有从设置的子页面返回设置时才保留记忆位置，从聊天等非设置页进入一律回到顶部
    val gotoSettings: (Boolean) -> Unit = { restoreScroll ->
        if (!restoreScroll) scope.launch { settingsScrollState.scrollTo(0) }
        nav.navigateUpTo(Screen.Settings.name)
    }
    // 从辅助错误toast跳设置并滚到对应配置区（SettingsScreen按scrollTarget定位）
    val gotoSettingsAt: (String) -> Unit = { target ->
        nav.push(Screen.Settings.name, mapOf("scrollTarget" to target))
    }

    // 返回上一级：全部交给backDispatcher+nav统一处理
    androidx.activity.compose.BackHandler(
        enabled = nav.canPop || backDispatcher.canIntercept(nav.current.route)
    ) {
        if (!backDispatcher.dispatch(nav.current.route)) nav.pop()
    }
    // 抽屉/模型选择器叠在导航之上，注册得更晚→优先消费返回
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }

    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providers.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val streamingConvId by vm.streamingConvId.collectAsState()
    val pendingConvId by vm.pendingConvId.collectAsState()
    // 忙碌态=生成中或存在等待用户操作的工具调用（审批/澄清）——输入栏按键在此期间保持"停止"
    val toolBusy by vm.hasPendingTools.collectAsState()
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    // 终端是全屏工具页：切换对话时自动退回聊天（否则用户无法从终端页切对话）
    LaunchedEffect(activeId) {
        if (nav.current.route == Screen.WorkspaceTerminal.name) nav.back()
    }

    // 调试球信号（系统悬浮窗→MainActivity extra→这里消费）
    val openDebugRequest by com.miniichatNext.carter.debug.DebugNavRequest.openDebug.collectAsState()
    LaunchedEffect(openDebugRequest) {
        if (openDebugRequest) {
            com.miniichatNext.carter.debug.DebugNavRequest.openDebug.value = false
            nav.push(Screen.Debug.name)
        }
    }
    // 点击调试球→弹应用内调试Dialog（折叠区块：日志等级/快速跳转）
    val openPanelRequest by com.miniichatNext.carter.debug.DebugNavRequest.openPanel.collectAsState()
    if (openPanelRequest) {
        com.miniichatNext.carter.ui.components.DebugBallDialog(
            settings = settings,
            onUpdate = { transform -> vm.updateSettings(transform) },
            onDismiss = { com.miniichatNext.carter.debug.DebugNavRequest.openPanel.value = false },
            onOpenDebugPage = { nav.push(Screen.Debug.name) },
        )
    }
        val fetchingId by vm.fetchingModelsFor.collectAsState()
    val assistants by vm.assistants.collectAsState()
    val skills by vm.skills.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val compressDialog by vm.compressDialog.collectAsState()
    val suggestionsGenerating by vm.suggestionsGenerating.collectAsState()
    val activeAssistant = assistants.firstOrNull { it.id == settings.activeAssistantId }

    val effectiveSkillIds: Set<String> by remember(activeId) {
        vm.effectiveSkillIdsFlow(activeId)
    }.collectAsState(emptySet<String>())
    val assistantConversations = conversations.filter { it.assistantId == settings.activeAssistantId }
    val activeConv = assistantConversations.firstOrNull { it.id == activeId }
    // busy态按对话隔离：对话a的流未结束不该让新建的对话显示停止键
    val conversationBusy = (isStreaming && streamingConvId == activeConv?.id) ||
        (toolBusy && pendingConvId == activeConv?.id)
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    // 工具开关可能在还没发消息的新会话里被点击，这里按需建一条会话
    val ensureConvId: () -> String = { activeId ?: vm.newConversation() }

    val clipboard = LocalClipboardManager.current
    val openProvidersLabel = stringResource(R.string.error_open_providers)
    // 服务商/模型类错误改用对话框展示：snackbar的action在部分机型上点了没反应，而AlertDialog是模态的
    var providerFixDialog by remember { mutableStateOf<String?>(null) }
    // 底部堆叠式toast：三通道统一写入一个栈（新条目在底部，旧的被顶上去）
    // 每条独立计时（从自己显示后2s），无跨条race；snackbarHost由ToastStackHost替换
    val toastItems by vm.toastItems.collectAsState()
    val copyAction: (com.miniichatNext.carter.data.model.ToastItem) -> Unit = { item ->
        clipboard.setText(AnnotatedString(item.message))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
    val manageAction: (com.miniichatNext.carter.data.model.ToastItem) -> Unit = { item ->
        (item.action as? com.miniichatNext.carter.data.model.ToastAction.Manage)?.let { gotoSettingsAt(it.kind) }
    }
    val onToastAction: (com.miniichatNext.carter.data.model.ToastItem) -> Unit = { item ->
        vm.removeToast(item.id)
        when (item.action) {
            is com.miniichatNext.carter.data.model.ToastAction.Copy -> copyAction(item)
            is com.miniichatNext.carter.data.model.ToastAction.Manage -> manageAction(item)
            com.miniichatNext.carter.data.model.ToastAction.None -> {}
        }
    }
    LaunchedEffect(Unit) {
        vm.toast.collect { msg ->
            vm.pushToast(com.miniichatNext.carter.data.model.ToastItem(
                message = msg,
                action = com.miniichatNext.carter.data.model.ToastAction.Copy,
                withDismiss = true,
            ))
        }
    }
    LaunchedEffect(Unit) {
        vm.auxError.collect { payload ->
            val kind = payload.substringBefore(':')
            vm.pushToast(com.miniichatNext.carter.data.model.ToastItem(
                message = payload.substringAfter(':'),
                action = com.miniichatNext.carter.data.model.ToastAction.Manage(kind),
                withDismiss = true,
            ))
        }
    }
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        val isHttpError = Regex("\\b(400|401|403|404|408|409|422|429|500|502|503|504)\\b").containsMatchIn(msg)
        val needsDialog = isHttpError
            || msg.contains("provider", ignoreCase = true)
            || msg.contains("api key", ignoreCase = true)
            || msg.contains("model", ignoreCase = true)
        if (needsDialog) {
            providerFixDialog = msg
        } else {
            vm.pushToast(com.miniichatNext.carter.data.model.ToastItem(
                message = msg,
                action = com.miniichatNext.carter.data.model.ToastAction.Copy,
                withDismiss = true,
            ))
        }
        vm.clearError()
    }

    providerFixDialog?.let { msg ->
        // 全局Md弹窗（api/MdDialog.kt）：错误消息走markdown渲染+字面\n换行
        com.miniichatNext.carter.ui.components.MdDialog(
            text = msg,
            confirmText = openProvidersLabel,
            onConfirm = {
                providerFixDialog = null
                nav.push(Screen.Providers.name)
            },
            dismissText = stringResource(R.string.cancel),
            onDismiss = { providerFixDialog = null },
        )
    }

    Scaffold(
        snackbarHost = {
            ToastStackHost(
                items = toastItems,
                onRemove = { id -> vm.removeToast(id) },
                onAction = onToastAction,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AppNavHost(nav = nav) { key ->
                when (Screen.entries.firstOrNull { it.name == key.route } ?: Screen.Chat) {
                    Screen.Chat -> {
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                GlassDrawer(
                                    conversations = assistantConversations,
                                    activeId = activeId,
                                    assistants = assistants,
                                    activeAssistantId = settings.activeAssistantId,
                                    onSelectAssistant = { vm.selectAssistant(it) },
                                    onSelect = { id ->
                                        vm.selectConversation(id)
                                        scope.launch { drawerState.close() }
                                    },
                                    onNew = {
                                        vm.newConversation()
                                        scope.launch { drawerState.close() }
                                    },
                                    onDelete = { vm.deleteConversation(it) },
                                    onRename = { id, t -> vm.renameConversation(id, t) },
                                    onOpenAssistants = {
                                        nav.push(Screen.Assistants.name)
                                        scope.launch { drawerState.close() }
                                    },
                                    onOpenSettings = {
                                        gotoSettings(false)
                                        scope.launch { drawerState.close() }
                                    }
                                )
                            }
                        ) {
                            ChatScreen(
                                conversation = activeConv,
                                settings = settings,
                                activeProvider = activeProvider,
                                isStreaming = conversationBusy,
                                streamingOverlay = streamingOverlay,
                                assistant = activeAssistant,
                                userProfile = userProfile,
                                assistants = assistants,
                                skills = skills,
                                effectiveSkillIds = effectiveSkillIds,
                                onToggleSkill = { id, en ->
                                    val cid = activeId
                                    if (cid != null) vm.toggleTemporarySkill(cid, id, en)
                                    else vm.toggleAssistantSkill(settings.activeAssistantId, id, en)
                                },
                                onSelectAssistant = { id -> vm.selectAssistant(id) },
                                onContextCompress = {
                                    vm.requestCompressDialog()
                                },
                                workspaces = workspaces,
                                effectiveWorkspaceId = effectiveWorkspaceId(activeConv, activeAssistant),
                                onPickWorkspace = { id -> vm.setConversationWorkspace(ensureConvId(), id) },
                                mcpServers = visibleMcpServers,
                                effectiveMcpServerIds = effectiveMcpServerIds(activeConv, activeAssistant),
                                onToggleMcpServer = { id, en ->
                                    vm.setConversationMcpServer(ensureConvId(), id, en)
                                },
                                onResetToolOverrides = { vm.clearConversationToolOverrides(ensureConvId()) },
                                onOpenTerminal = {
                                    DebugLog.i("Nav", "+ menu terminal tapped")
                                    // 终端不依赖对话绑定：优先绑定的工作区，否则最近使用的工作区
                                    val wsId = effectiveWorkspaceId(activeConv, activeAssistant)
                                    val ws = wsId?.let { id -> workspaces.firstOrNull { it.id == id } }
                                        ?: workspaces.maxByOrNull { it.lastAccessAt ?: it.updatedAt }
                                    if (ws == null) {
                                        vm.pushToast(com.miniichatNext.carter.data.model.ToastItem(
                                            message = context.getString(
                                                com.miniichatNext.carter.R.string.workspace_terminal_unbound
                                            ),
                                        ))
                                    } else {
                                        DebugLog.i("Nav", "open terminal: root=" + ws.root)
                                        nav.push(
                                            Screen.WorkspaceTerminal.name,
                                            mapOf("workspaceId" to ws.id, "root" to ws.root, "name" to ws.name),
                                        )
                                    }
                                },
                                onManageSkills = { nav.push(Screen.Skills.name) },
                                onManageWorkspaces = { nav.push(Screen.Workspace.name) },
                                onManageMcp = { nav.push(Screen.Mcp.name) },
                                onApproveTool = { inv, custom -> vm.approveToolCall(inv, custom) },
                                onRejectTool = { inv -> vm.rejectToolCall(inv) },
                                onSuggestions = { anchorMsgId, page, manual -> vm.generateSuggestions(anchorMsgId, page, manual) },
                                suggestionsGenerating = suggestionsGenerating,
                                onRenameChat = { newTitle -> vm.renameConversation(activeId ?: "", newTitle) },
                                onMenu = { scope.launch { drawerState.open() } },
                                onSend = { text, atts -> vm.sendMessage(text, atts) },
                                onStop = { vm.stopStreaming() },
                                onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                                onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                                onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                                onContinue = { vm.continueGenerating() },
                                vm = vm,
                                onNew = { vm.newConversation() },
                                onOpenSettings = { gotoSettings(false) },
                                onFullscreen = { code, dark ->
                                    nav.push(Screen.MermaidPreview.name, mapOf("code" to code, "dark" to "$dark"))
                                },
                                onPickModel = {
                                    if (providers.isEmpty()) {
                                        editingProvider = null
                                        nav.push(Screen.ProviderEdit.name)
                                    } else showModelPicker = true
                                }
                            )
                        }
                    }

                    Screen.Settings -> {
                        SettingsScreen(
                            settings = settings,
                            providers = providers,
                            assistants = assistants,
                            skills = skills,
                            userProfile = userProfile,
                            scrollTarget = nav.current.args["scrollTarget"],
                            onBack = { nav.back() },
                            onChange = { vm.updateSettings(it) },
                            onOpenProviders = { nav.push(Screen.Providers.name) },
                            onOpenAssistants = { nav.push(Screen.Assistants.name) },
                            onOpenSkills = { nav.push(Screen.Skills.name) },
                            onOpenUserProfile = { nav.push(Screen.UserProfile.name) },
                            onOpenAbout = { nav.push(Screen.About.name) },
                            onOpenUpdateSettings = { nav.push(Screen.UpdateSettings.name) },
                            onOpenAcknowledgements = { nav.push(Screen.Acknowledgements.name) },
                            onOpenWorkspace = { nav.push(Screen.Workspace.name) },
                            onOpenMcp = { nav.push(Screen.Mcp.name) },
                            onOpenBuiltInTools = { nav.push(Screen.McpBuiltIn.name) },
                            scrollState = settingsScrollState
                        )
                    }

                    Screen.Mcp -> {
                        McpServersScreen(
                            mcpStore = vm.mcpStore,
                            onBack = { nav.back() },
                            onAdd = { nav.push(Screen.McpEdit.name) },
                            onOpenServerDetail = { id ->
                                nav.push(Screen.McpDetail.name, mapOf("serverId" to id))
                            },
                            onOpenBuiltInTools = { nav.push(Screen.McpBuiltIn.name) },
                        )
                    }

                    Screen.McpDetail -> {
                        val serverId = nav.current.args["serverId"].orEmpty()
                        McpServerDetailScreen(
                            mcpStore = vm.mcpStore,
                            serverId = serverId,
                            onBack = { nav.back() },
                            onEdit = { nav.push(Screen.McpEdit.name, mapOf("serverId" to serverId)) },
                            onDeleted = { nav.popUpTo(Screen.Mcp.name) },
                        )
                    }

                    Screen.McpEdit -> {
                        val editingId = nav.current.args["serverId"]
                        McpServerEditorScreen(
                            initial = mcpServers.firstOrNull { it.id == editingId },
                            onBack = { nav.back() },
                            onSaved = { entity ->
                                scope.launch { vm.mcpStore.upsertServer(entity) }
                                nav.back()
                            },
                            onSyncNow = { entity ->
                                runCatching {
                                    val fetched = McpClient().listTools(entity)
                                    vm.mcpStore.replaceToolsForServer(entity.id, fetched)
                                    vm.mcpStore.upsertServer(entity.copy(toolsFetched = true))
                                    fetched.size
                                }
                            },
                        )
                    }

                    Screen.McpBuiltIn -> {
                        BuiltInToolsScreen(mcpStore = vm.mcpStore, onBack = { nav.back() }, backDispatcher = backDispatcher)
                    }

                    Screen.MermaidPreview -> {
                        com.miniichatNext.carter.ui.markdown.MermaidPreviewScreen(
                            code = nav.current.args["code"].orEmpty(),
                            dark = nav.current.args["dark"] == "true",
                            onBack = { nav.back() },
                        )
                    }

                    Screen.Workspace -> {
                        WorkspaceListScreen(
                            repository = vm.workspaceRepository,
                            onBack = { nav.back() },
                            onCreate = {
                                scope.launch {
                                    val ws = vm.workspaceRepository.create("Workspace")
                                    nav.push(Screen.WorkspaceDetail.name, mapOf("workspaceId" to ws.id))
                                }
                            },
                            onCreateNamed = { name ->
                                scope.launch {
                                    val ws = vm.workspaceRepository.create(name)
                                    nav.push(Screen.WorkspaceDetail.name, mapOf("workspaceId" to ws.id))
                                }
                            },
                            onWorkspaceClick = { id ->
                                nav.push(Screen.WorkspaceDetail.name, mapOf("workspaceId" to id))
                            },
                        )
                    }

                    Screen.WorkspaceDetail -> {
                        WorkspaceDetailScreen(
                            repository = vm.workspaceRepository,
                            workspaceId = nav.current.args["workspaceId"].orEmpty(),
                            onBack = { nav.back() },
                            onOpenTerminal = {
                                DebugLog.i("Nav", "+ menu terminal tapped")
                                // 终端不依赖对话绑定：优先绑定的工作区，否则最近使用的工作区
                                val wsId = effectiveWorkspaceId(activeConv, activeAssistant)
                                DebugLog.i(
                                    "Nav",
                                    "open terminal: wsId=" + (wsId ?: "unbound") +
                                        " recentWs=" + (workspaces.maxByOrNull { it.lastAccessAt ?: it.updatedAt }?.root ?: "none")
                                )
                                val ws = wsId?.let { id -> workspaces.firstOrNull { it.id == id } }
                                    ?: workspaces.maxByOrNull { it.lastAccessAt ?: it.updatedAt }
                                if (ws == null) {
                                    vm.pushToast(com.miniichatNext.carter.data.model.ToastItem(
                                        message = context.getString(
                                            com.miniichatNext.carter.R.string.workspace_terminal_unbound
                                        ),
                                    ))
                                } else {
                                    nav.push(
                                        Screen.WorkspaceTerminal.name,
                                        mapOf(
                                            "workspaceId" to ws.id,
                                            "root" to ws.root,
                                            "name" to ws.name,
                                        ),
                                    )
                                }
                            },
                        )
                    }

                    Screen.WorkspaceTerminal -> {
                        val terminalWs = workspaces.firstOrNull { it.id == nav.current.args["workspaceId"] }
                        com.miniichatNext.carter.ui.workspace.WorkspaceTerminalScreen(
                            workspaceId = terminalWs?.id ?: "",
                            workspaceRoot = terminalWs?.root,
                            workspaceName = terminalWs?.name ?: "",
                            onBack = { nav.back() },
                            shellCompatibilityMode = terminalWs?.shellCompatibilityMode == true,
                        )
                    }

                    Screen.About -> {
                        AboutScreen(
                            onBack = { nav.back() },
                            onOpenDebug = { nav.push(Screen.Debug.name) },
                        )
                    }

                    Screen.ThemeColors -> {
                        com.miniichatNext.carter.debug.ui.ThemeColorsScreen(
                            onBack = { nav.back() },
                        )
                    }

                    Screen.Acknowledgements -> {
                        com.miniichatNext.carter.ui.settings.AcknowledgementsScreen(
                            onBack = { nav.back() },
                        )
                    }

                    Screen.UpdateSettings -> {
                        com.miniichatNext.carter.ui.settings.UpdateSettingsScreen(
                            onBack = { nav.back() },
                        )
                    }

                    Screen.Debug -> {
                        DebugScreen(
                            onBack = { nav.back() },
                            vm = vm,
                            onOpenDialogTest = { nav.push(Screen.DialogTest.name) },
                                onOpenThemeColors = { nav.push(Screen.ThemeColors.name) },
                        )
                    }
                    Screen.DialogTest -> {
                        DialogTestScreen(onBack = { nav.back() }, vm = vm)
                    }

                    Screen.Assistants -> {
                        AssistantsScreen(
                            assistants = assistants,
                            activeId = settings.activeAssistantId,
                            onBack = { nav.back() },
                            onSelect = { vm.selectAssistant(it) },
                            onUpsert = { vm.upsertAssistant(it) },
                            onDelete = { vm.deleteAssistant(it) },
                            onEdit = { a ->
                                editingAssistant = a
                                nav.push(Screen.AssistantEdit.name)
                            }
                        )
                    }

                    Screen.AssistantEdit -> {
                        AssistantEditorScreen(
                            initial = editingAssistant,
                            availableSkills = skills,
                            availableMcpServers = visibleMcpServers,
                            // 只暴露已就绪（rootfs已安装）的工作区给助手绑定
                            availableWorkspaces = workspaces.filter {
                                it.shellStatus == com.miniichatNext.carter.data.workspace.WorkspaceShellStatus.READY.name
                            },
                            onCancel = { nav.back() },
                            onSave = { a ->
                                vm.upsertAssistant(a)
                                editingAssistant = null
                                nav.back()
                            },
                            onDelete = {
                                editingAssistant?.let { vm.deleteAssistant(it.id) }
                                editingAssistant = null
                                nav.back()
                            },
                            onManageSkills = { nav.push(Screen.Skills.name) },
                            onManageMcp = { nav.push(Screen.Mcp.name) },
                            onManageWorkspaces = { nav.push(Screen.Workspace.name) }
                        )
                    }

                    Screen.Providers -> {
                        ProvidersScreen(
                            providers = providers,
                            fetchingId = fetchingId,
                            activeProviderId = settings.activeProviderId,
                            activeModel = settings.activeModel,
                            onBack = { nav.back() },
                            onCreate = {
                                editingProvider = null
                                nav.push(Screen.ProviderEdit.name)
                            },
                            onEdit = { p ->
                                editingProvider = p
                                nav.push(Screen.ProviderEdit.name)
                            },
                            onDelete = { vm.deleteProvider(it) },
                            onFetchModels = { vm.fetchModels(it) },
                            onAddManualModel = { id, m -> vm.addManualModel(id, m) },
                            onRemoveModel = { id, m -> vm.removeModel(id, m) },
                            onSelectModel = { id, m -> vm.selectModel(id, m) },
                            onEditModel = { p, m ->
                                editingProvider = p
                                editingModelId = m
                                nav.push(Screen.ModelEdit.name)
                            }
                        )
                    }

                    Screen.ModelEdit -> {
                        val mp = editingProvider ?: return@AppNavHost
                        val mid = editingModelId ?: ""
                        com.miniichatNext.carter.ui.provider.ModelEditScreen(
                            provider = mp,
                            modelId = mid,
                            onBack = {
                                editingModelId = null
                                editingProvider = null
                                nav.back()
                            },
                            onSave = { cfg ->
                                vm.updateModelConfig(mp.id, mid) { cfg }
                                editingModelId = null
                                editingProvider = null
                            },
                        )
                    }

                    Screen.ProviderEdit -> {
                        ProviderEditorScreen(
                            initial = editingProvider,
                            onCancel = {
                                editingProvider = null
                                nav.back()
                            },
                            onSave = { p ->
                                vm.upsertProvider(p)
                                editingProvider = null
                                nav.back()
                            }
                        )
                    }

                    Screen.Skills -> {
                        SkillsScreen(
                            skills = skills,
                            onBack = { nav.back() },
                            onSave = { vm.saveSkill(it) },
                            onDelete = { vm.deleteSkill(it) },
                            onSetEnabled = { id, en -> vm.setSkillEnabled(id, en) },
                            onImportError = { vm.setError(it) },
                            onImportSuccess = { name ->
                                vm.setToast(context.getString(R.string.skill_imported, name))
                            },
                            onSaveFiles = { name, files -> vm.saveSkillFiles(name, files) }
                        )
                    }

                    Screen.UserProfile -> {
                        UserProfileScreen(
                            profile = userProfile,
                            onBack = { nav.back() },
                            onSave = { newProfile -> vm.updateUserProfile { newProfile } }
                        )
                    }
                }
            }

        }
    }

    // 如果上次运行发生崩溃，再次启动时优先展示崩溃报告页
    var pendingCrash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (CrashLogger.hasCrash(context)) pendingCrash = true
    }
    if (pendingCrash) {
        CrashReportScreen(onDismiss = {
            CrashLogger.clearLatestCrash(context)
            pendingCrash = false
        })
    }

    if (showModelPicker) {
        // 必须用和生成时完全相同的provider解析顺序，否则UI写进去的key与请求读的key对不上→开关看着能点、实际不生效
        // 顺序：助手的preferredProviderId>全局activeProviderId
        val pickerConv = conversations.firstOrNull { it.id == activeId }
        val pickerAssistant = assistants.firstOrNull {
            it.id == (pickerConv?.assistantId ?: settings.activeAssistantId)
        }
        val pickerProviderId = pickerAssistant?.preferredProviderId?.takeIf { it.isNotBlank() }
            ?: settings.activeProviderId
        // 没有活跃对话时也要能开（否则所有的设置项都会被静默丢弃）
        val pickerConvId = activeId ?: vm.ensureActiveConversationId()

        ModelPickerSheet(
            providers = providers,
            activeProviderId = pickerProviderId,
            activeModel = settings.activeModel,
            onPick = { pid, m -> vm.selectModel(pid, m) },
            onDismiss = { showModelPicker = false },
            // 思考等级写到对话切换服务商/模型时仍然保留
            onThinkingChange = { lvl -> vm.setConversationThinkingLevel(pickerConvId, lvl) },
            onResponseApiChange = { on ->
                vm.setConversationProviderOverride(pickerConvId, pickerProviderId) {
                    it.copy(responseApi = on)
                }
            },
            onPromptCacheChange = { on ->
                vm.setConversationProviderOverride(pickerConvId, pickerProviderId) {
                    it.copy(promptCache = on)
                }
            },
            providerOverride = pickerConv?.providerOverride(pickerProviderId)
                ?: ProviderOverride(),
            pickerConversation = pickerConv,
        )
    }

    if (compressDialog) {
        CompressContextDialog(
            onDismiss = { vm.dismissCompressDialog() },
            onConfirm = { targetTokens, keepRecent, additionalPrompt ->
                vm.compressContext(targetTokens, keepRecent, additionalPrompt)
                vm.dismissCompressDialog()
            }
        )
    }

    // 全局更新弹窗：监听UpdateState.pending（启动auto-check/AboutScreen手动检查都会触发）
    pendingUpdate?.let { info ->
        com.miniichatNext.carter.ui.settings.update.UpdateDialog(
            info = info,
            currentVersion = currentVersionName,
            onDismiss = { com.miniichatNext.carter.data.update.UpdateState.clearPending() },
            onFullscreen = { code, dark ->
                nav.push(Screen.MermaidPreview.name, mapOf("code" to code, "dark" to "$dark"))
            },
            // "忽略此版本"交给onIgnore：持久化+关闭。内置按钮[忽略][下载]顺序由UpdateDialog决定
            onIgnore = if (!info.compulsory) {
                {
                    scope.launch {
                        settingsRepo.ignoreUpdateVersion(info.version)
                        com.miniichatNext.carter.data.update.UpdateState.addIgnoredVersion(info.version)
                    }
                    com.miniichatNext.carter.data.update.UpdateState.clearPending()
                }
            } else null,
        )
    }
}

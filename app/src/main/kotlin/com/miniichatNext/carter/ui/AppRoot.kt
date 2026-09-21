package com.miniichatNext.carter.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
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
import kotlinx.coroutines.launch
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.assistant.AssistantEditorScreen
import com.miniichatNext.carter.ui.assistant.AssistantsScreen
import com.miniichatNext.carter.ui.chat.ChatScreen
import com.miniichatNext.carter.ui.chat.CompressContextDialog
import com.miniichatNext.carter.ui.chat.ModelPickerSheet
import com.miniichatNext.carter.data.mcp.McpClient
import com.miniichatNext.carter.ui.mcp.BuiltInToolsScreen
import com.miniichatNext.carter.ui.mcp.McpServerDetailScreen
import com.miniichatNext.carter.ui.mcp.McpServerEditorScreen
import com.miniichatNext.carter.ui.mcp.McpServersScreen
import com.miniichatNext.carter.ui.navigation.AppBackDispatcher
import com.miniichatNext.carter.ui.navigation.AppNavHost
import com.miniichatNext.carter.ui.navigation.NavController
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

/** 路由表：新增页面只需在这里加一项（配合AppNavRegistry可自定义过渡动画） */
internal enum class Screen {
    Chat, Settings, Providers, ProviderEdit, Assistants, AssistantEdit,
    Skills, UserProfile, About, Debug,
    Workspace, WorkspaceDetail,
    Mcp, McpDetail, McpEdit, McpBuiltIn
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val mcpServers by vm.mcpServers.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 统一导航：返回栈 + 过渡动画（动画可在AppNavRegistry里按路由定制）
    val nav = rememberSaveable(saver = NavController.Saver) { NavController(Screen.Chat.name) }
    // 统一返回调度：页面可注册层级内返回（如MCP详情→列表），未消费时才弹栈
    val backDispatcher = remember { AppBackDispatcher() }
    // 后期自定义示例：给单个路由换一套动画
    //   AppNavRegistry.registerRouteTransition(Screen.Debug.name, NavTransitionPresets.Scale.forward)
    // 后期自定义示例：注册全局返回兜底（比如“再按一次退出”）
    //   backDispatcher.fallback = { ... ; true }

    LaunchedEffect(nav.current.route) {
        DebugLog.v("Nav", "route -> ${nav.current.route} (depth=${nav.current.depth})")
    }

    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingAssistant by remember { mutableStateOf<Assistant?>(null) }
    // 把Settings滚动状态提升到AppRoot（用rememberSaveable持久化），导航到关于页再返回时滚动位置不会跳到顶部
    val settingsScrollState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = ScrollState.Saver
    ) { ScrollState(0) }
    // 滚动位置恢复策略：只有从设置的子页面返回设置时才保留记忆位置，从聊天等非设置页进入一律回到顶部
    val gotoSettings: (Boolean) -> Unit = { restoreScroll ->
        if (!restoreScroll) scope.launch { settingsScrollState.scrollTo(0) }
        nav.navigateUpTo(Screen.Settings.name)
    }

    // ─返回上一级：全部交给backDispatcher+nav统一处理
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
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
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
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    // 工具开关可能在还没发消息的新会话里被点击，这里按需建一条会话
    val ensureConvId: () -> String = { activeId ?: vm.newConversation() }

    val snackbar = remember { SnackbarHostState() }
    val openProvidersLabel = stringResource(R.string.error_open_providers)
    // 服务商/模型类错误改用对话框展示：snackbar的action在部分机型上点了没反应，而AlertDialog是模态的
    var providerFixDialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearToast()
        }
    }
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        val needsProviderFix = msg.contains("provider", ignoreCase = true)
            || msg.contains("api key", ignoreCase = true)
            || msg.contains("model", ignoreCase = true)
            || msg.contains("401")
            || msg.contains("403")
        if (needsProviderFix) {
            providerFixDialog = msg
        } else {
            snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
        vm.clearError()
    }

    providerFixDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { providerFixDialog = null },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    providerFixDialog = null
                    nav.push(Screen.Providers.name)
                }) { Text(openProvidersLabel) }
            },
            dismissButton = {
                TextButton(onClick = { providerFixDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
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
                                isStreaming = isStreaming,
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
                                mcpServers = mcpServers,
                                effectiveMcpServerIds = effectiveMcpServerIds(activeConv, activeAssistant),
                                onToggleMcpServer = { id, en ->
                                    vm.setConversationMcpServer(ensureConvId(), id, en)
                                },
                                onResetToolOverrides = { vm.clearConversationToolOverrides(ensureConvId()) },
                                onManageWorkspaces = { nav.push(Screen.Workspace.name) },
                                onManageMcp = { nav.push(Screen.Mcp.name) },
                                onApproveTool = { inv, custom -> vm.approveToolCall(inv, custom) },
                                onRejectTool = { inv -> vm.rejectToolCall(inv) },
                                onSuggestions = { page -> vm.generateSuggestions(page) },
                                suggestionsGenerating = suggestionsGenerating,
                                onRenameChat = { newTitle -> vm.renameConversation(activeId ?: "", newTitle) },
                                onMenu = { scope.launch { drawerState.open() } },
                                onSend = { text, atts -> vm.sendMessage(text, atts) },
                                onStop = { vm.stopStreaming() },
                                onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                                onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                                onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                                onContinue = { vm.continueGenerating() },
                                onNew = { vm.newConversation() },
                                onOpenSettings = { gotoSettings(false) },
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
                            onBack = { nav.back() },
                            onChange = { vm.updateSettings(it) },
                            onOpenProviders = { nav.push(Screen.Providers.name) },
                            onOpenAssistants = { nav.push(Screen.Assistants.name) },
                            onOpenSkills = { nav.push(Screen.Skills.name) },
                            onOpenUserProfile = { nav.push(Screen.UserProfile.name) },
                            onOpenAbout = { nav.push(Screen.About.name) },
                            onOpenWorkspace = { nav.push(Screen.Workspace.name) },
                            onOpenMcp = { nav.push(Screen.Mcp.name) },
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
                        BuiltInToolsScreen(mcpStore = vm.mcpStore, onBack = { nav.back() })
                    }

                    Screen.Workspace -> {
                        WorkspaceListScreen(
                            repository = vm.workspaceRepository,
                            onBack = { nav.back() },
                            onOpen = { id ->
                                nav.push(Screen.WorkspaceDetail.name, mapOf("workspaceId" to id))
                            },
                        )
                    }

                    Screen.WorkspaceDetail -> {
                        WorkspaceDetailScreen(
                            repository = vm.workspaceRepository,
                            workspaceId = nav.current.args["workspaceId"].orEmpty(),
                            onBack = { nav.back() },
                        )
                    }

                    Screen.About -> {
                        AboutScreen(
                            onBack = { nav.back() },
                            onOpenDebug = { nav.push(Screen.Debug.name) }
                        )
                    }

                    Screen.Debug -> {
                        DebugScreen(onBack = { nav.back() })
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
                            availableMcpServers = mcpServers,
                            availableWorkspaces = workspaces,
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
                            }
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
                            onSelectModel = { id, m -> vm.selectModel(id, m) }
                        )
                    }

                    Screen.ProviderEdit -> {
                        ProviderEditorScreen(
                            initial = editingProvider,
                            onCancel = { nav.back() },
                            onSave = { p ->
                                vm.upsertProvider(p)
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
        // 顺序：助手的preferredProviderId > 全局activeProviderId
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
}

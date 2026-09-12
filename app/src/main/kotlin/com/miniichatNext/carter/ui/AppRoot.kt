package com.miniichatNext.carter.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.miniichatNext.carter.ChatViewModel
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.ProviderConfig
import com.miniichatNext.carter.Debug.CrashLogger
import com.miniichatNext.carter.Debug.CrashReportScreen
import com.miniichatNext.carter.Debug.DebugScreen
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Providers, ProviderEdit, Assistants, AssistantEdit, Skills, UserProfile, About, Debug }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    // 导航埋点：切换页面记录一条
    LaunchedEffect(screen) {
        com.miniichatNext.carter.Debug.DebugLog.v("Nav", "screen -> " + screen.name)
    }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingAssistant by remember { mutableStateOf<com.miniichatNext.carter.data.Assistant?>(null) }
    // 把Settings滚动状态提升到AppRoot（用rememberSaveable 持久化），导航到关于页再返回时滚动位置不会跳到顶部
    val settingsScrollState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = ScrollState.Saver
    ) { ScrollState(0) }
    // 滚动位置恢复策略：只有从设置的子页面返回设置时才保留记忆位置
    // 从聊天等非设置页进入设置时一律回到顶部
    val gotoSettings: (Boolean) -> Unit = { restoreScroll ->
        if (!restoreScroll) scope.launch { settingsScrollState.scrollTo(0) }
        screen = Screen.Settings
    }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Providers) {
        gotoSettings(true)
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ProviderEdit) {
        screen = Screen.Providers
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Assistants) {
        gotoSettings(true)
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.AssistantEdit) {
        screen = Screen.Assistants
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Skills) {
        gotoSettings(true)
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.UserProfile) {
        gotoSettings(true)
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.About) {
        gotoSettings(true)
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Debug) {
        screen = Screen.About
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
    val activeAssistant = assistants.firstOrNull { it.id == settings.activeAssistantId }

    val effectiveSkillIds: Set<String> by remember(activeId) {
        vm.effectiveSkillIdsFlow(activeId)
    }.collectAsState(emptySet<String>())
    val assistantConversations = conversations.filter { it.assistantId == settings.activeAssistantId }
    val activeConv = assistantConversations.firstOrNull { it.id == activeId }
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }

    val snackbar = remember { SnackbarHostState() }
    val openProvidersLabel = stringResource(R.string.error_open_providers)
    val skillImportedFmt = stringResource(R.string.skill_imported)
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
        val result = snackbar.showSnackbar(
            message = msg,
            actionLabel = if (needsProviderFix) openProvidersLabel else null,
            duration = SnackbarDuration.Long
        )
        vm.clearError()
        if (result == SnackbarResult.ActionPerformed) {
            screen = Screen.Providers
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (screen) {
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
                                    screen = Screen.Assistants
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
                            onSuggestions = { page -> vm.generateSuggestions(page) },
                            onRenameChat = { newTitle -> vm.renameConversation(activeId ?: "", newTitle) },
                            onMenu = { scope.launch { drawerState.open() } },
                            onSend = { text, atts -> vm.sendMessage(text, atts) },
                            onStop = { vm.stopStreaming() },
                            onRegenerate = { vm.regenerate() },
                            onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                            onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                            onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                            onContinue = { vm.continueGenerating() },
                            onNew = { vm.newConversation() },
                            onOpenSettings = { gotoSettings(false) },
                            onPickModel = {
                                if (providers.isEmpty()) {
                                    editingProvider = null
                                    screen = Screen.ProviderEdit
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
                        onBack = { screen = Screen.Chat },
                        onChange = { vm.updateSettings(it) },
                        onOpenProviders = { screen = Screen.Providers },
                        onOpenAssistants = { screen = Screen.Assistants },
                        onOpenSkills = { screen = Screen.Skills },
                        onOpenUserProfile = { screen = Screen.UserProfile },
                        onOpenAbout = { screen = Screen.About },
                        scrollState = settingsScrollState
                    )
                }
                Screen.About -> {
                    AboutScreen(
                        onBack = { gotoSettings(true) },
                        onOpenDebug = { screen = Screen.Debug }
                    )
                }
                Screen.Debug -> {
                    DebugScreen(onBack = { screen = Screen.About })
                }
                Screen.Assistants -> {
                    AssistantsScreen(
                        assistants = assistants,
                        activeId = settings.activeAssistantId,
                        onBack = { gotoSettings(true) },
                        onSelect = { vm.selectAssistant(it) },
                        onUpsert = { vm.upsertAssistant(it) },
                        onDelete = { vm.deleteAssistant(it) },
                        onEdit = { a ->
                            editingAssistant = a
                            screen = Screen.AssistantEdit
                        }
                    )
                }
                Screen.AssistantEdit -> {
                    AssistantEditorScreen(
                        initial = editingAssistant,
                        availableSkills = skills,
                        onCancel = { screen = Screen.Assistants },
                        onSave = { a ->
                            vm.upsertAssistant(a)
                            editingAssistant = null
                            screen = Screen.Assistants
                        },
                        onDelete = {
                            editingAssistant?.let { vm.deleteAssistant(it.id) }
                            editingAssistant = null
                            screen = Screen.Assistants
                        }
                    )
                }
                Screen.Providers -> {
                    ProvidersScreen(
                        providers = providers,
                        fetchingId = fetchingId,
                        activeProviderId = settings.activeProviderId,
                        activeModel = settings.activeModel,
                        onBack = { gotoSettings(true) },
                        onCreate = {
                            editingProvider = null
                            screen = Screen.ProviderEdit
                        },
                        onEdit = { p ->
                            editingProvider = p
                            screen = Screen.ProviderEdit
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
                        onCancel = { screen = Screen.Providers },
                        onSave = { p ->
                            vm.upsertProvider(p)
                            screen = Screen.Providers
                        }
                    )
                }
                Screen.Skills -> {
                    com.miniichatNext.carter.ui.skills.SkillsScreen(
                        skills = skills,
                        onBack = { gotoSettings(true) },
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
                    com.miniichatNext.carter.ui.UserProfileScreen(
                        profile = userProfile,
                        onBack = { gotoSettings(true) },
                        onSave = { newProfile -> vm.updateUserProfile { newProfile } }
                    )
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
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            onPick = { pid, m -> vm.selectModel(pid, m) },
            onDismiss = { showModelPicker = false }
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

package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.ui.chat.input.InputBar
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Attachment
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.skills.Skill
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.vm.effectiveSkillIds
import com.miniichatNext.carter.vm.suggestionCacheKey
import com.miniichatNext.carter.vm.ChatViewModel
import com.miniichatNext.carter.vm.StreamOverlay
import androidx.compose.runtime.collectAsState
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.ui.markdown.preloadMarkdownDocument

@Composable
private fun ChatMessageListItem(
    message: com.miniichatNext.carter.data.model.Message,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    streamingOverlay: StateFlow<StreamOverlay?>?,
    userAvatar: com.miniichatNext.carter.data.avatar.Avatar,
    assistantAvatar: com.miniichatNext.carter.data.avatar.Avatar,
    senderLabel: String,
    onStartEdit: () -> Unit,
    onContinue: () -> Unit,
    onDelete: (String) -> Unit,
    onRegenerateFrom: (String) -> Unit,
    onApproveTool: ((com.miniichatNext.carter.data.model.ToolInvocation, String?) -> Unit)?,
    onRejectTool: ((com.miniichatNext.carter.data.model.ToolInvocation) -> Unit)?,
    onFullscreen: ((String, Boolean) -> Unit)?,
) {
    val liveOverlay = if (isLastAssistant && isStreaming) {
        streamingOverlay?.collectAsState(initial = null)?.value
    } else null
    val displayMessage = remember(message, liveOverlay) {
        liveOverlay?.let { overlay ->
            message.copy(
                content = overlay.content,
                reasoningContent = overlay.reasoning
                    .ifBlank { message.reasoningContent.orEmpty() }
                    .takeIf { it.isNotBlank() },
                orderedParts = overlay.parts.ifEmpty { message.orderedParts },
            )
        } ?: message
    }
    MessageItem(
        message = displayMessage,
        avatar = if (displayMessage.role == "user") userAvatar else assistantAvatar,
        senderLabel = senderLabel,
        isLastAssistant = isLastAssistant,
        isStreaming = isStreaming,
        onStartEdit = onStartEdit,
        onContinue = onContinue,
        onDelete = { onDelete(displayMessage.id) },
        onRegenerateFrom = { onRegenerateFrom(displayMessage.id) },
        onApproveTool = onApproveTool,
        onRejectTool = onRejectTool,
        onFullscreen = onFullscreen,
    )
}

@Composable
fun ChatScreen(
    conversation: Conversation?,
    settings: AppSettings,
    activeProvider: ProviderConfig?,
    /** 本对话实际生效的模型id（对话覆盖>助手preferred>全局默认），气泡标签与enabled用它 */
    effectiveModelId: String = "",
    isStreaming: Boolean,
    streamingOverlay: StateFlow<StreamOverlay?>? = null,
    assistant: com.miniichatNext.carter.data.model.Assistant? = null,
    userProfile: com.miniichatNext.carter.data.model.UserProfile = com.miniichatNext.carter.data.model.UserProfile(),
    assistants: List<com.miniichatNext.carter.data.model.Assistant> = emptyList(),
    skills: List<com.miniichatNext.carter.data.skills.Skill> = emptyList(),
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onContextCompress: () -> Unit = {},
    onSuggestions: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    suggestionsGenerating: Set<String> = emptySet(),
    onRenameChat: (String) -> Unit = {},
    effectiveSkillIds: Set<String> = emptySet(),
    onSelectAssistant: (String) -> Unit = {},
    onMenu: () -> Unit,
    onSend: (String, List<com.miniichatNext.carter.data.model.Attachment>) -> Unit,
    onStop: () -> Unit,
    onRegenerateFrom: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onContinue: () -> Unit = {},
    // VM注入：用于权限请求弹窗（pendingManageStorage StateFlow）
    vm: ChatViewModel? = null,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
    // 对话级工具开关（「+」菜单→工作区/MCP弹窗）
    workspaces: List<com.miniichatNext.carter.data.workspace.WorkspaceEntity> = emptyList(),
    effectiveWorkspaceId: String? = null,
    onPickWorkspace: (String?) -> Unit = {},
    mcpServers: List<com.miniichatNext.carter.data.mcp.McpServerConfig> = emptyList(),
    effectiveMcpServerIds: Set<String> = emptySet(),
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> },
    onResetToolOverrides: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onManageSkills: () -> Unit = {},
    onManageWorkspaces: () -> Unit = {},
    onManageMcp: () -> Unit = {},
    onApproveTool: ((com.miniichatNext.carter.data.model.ToolInvocation, String?) -> Unit)? = null,
    onRejectTool: ((com.miniichatNext.carter.data.model.ToolInvocation) -> Unit)? = null
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<com.miniichatNext.carter.data.model.Attachment>>(emptyList()) }
    val listState = rememberLazyListState()
    val rawMessages = conversation?.messages ?: emptyList()
    var followBottom by remember(conversation?.id) { mutableStateOf(true) }
    var rememberedSession by rememberSaveable { mutableStateOf("") }
    var rememberedCount by rememberSaveable { mutableIntStateOf(-1) }
    // 流式overlay只由最后一条生成中的消息订阅，历史消息保持稳定节点。
    val visibleMessages = remember(rawMessages) { rawMessages.filterNot { it.hidden } }
    LaunchedEffect(conversation?.id, visibleMessages.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            val indexes = info.visibleItemsInfo.map { it.index }.filter { it in visibleMessages.indices }
            if (indexes.isEmpty()) null else (indexes.minOrNull()!! to indexes.maxOrNull()!!)
        }
            .distinctUntilChanged()
            .collectLatest { range ->
                if (range == null) return@collectLatest
                delay(70)
                val (first, last) = range
                val indexesToPrewarm = ((first - 2).coerceAtLeast(0) until
                    (last + 3).coerceAtMost(visibleMessages.size))
                    .sortedBy { index -> if (index < first) first - index else index - first }
                indexesToPrewarm.forEach { index ->
                    val message = visibleMessages[index]
                    if (message.role == "assistant" && message.content.length > 512) {
                        preloadMarkdownDocument(message.content)
                    }
                }
            }
    }
    val assistantDisplayAvatar = remember(assistant?.avatar, assistant?.avatarPath) {
        com.miniichatNext.carter.data.avatar.Avatar.fromLegacy(
            assistant?.avatar ?: "🤖", assistant?.avatarPath
        )
    }
    val userSenderLabel = stringResource(R.string.profile_default_name)
    val assistantSenderLabel = effectiveModelId.ifBlank {
        activeProvider?.name ?: stringResource(R.string.assistant)
    }
    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    // 重说：先确认，再截断重生成
    var retellTargetId by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    // 两个内嵌面板：+面板与候选回复面板（互斥，点空白处统一收起）
    var addPanelOpen by rememberSaveable { mutableStateOf(false) }
    var candidatesOpen by rememberSaveable { mutableStateOf(false) }
    // 候选词当前页（0-based）
    var suggestionPage by rememberSaveable { mutableStateOf(0) }
    // 递增即请求一次"聚焦输入框+弹键盘"（候选回复点"编辑"时用）
    var focusInputRequest by remember { mutableStateOf(0) }

    val followThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(conversation?.id) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (!scrolling) return@collectLatest
                var previousFrame = 0L
                var lastReport = 0L
                while (listState.isScrollInProgress) {
                    val now = withFrameNanos { it }
                    if (previousFrame != 0L) {
                        val gapMs = (now - previousFrame) / 1_000_000L
                        val wallMs = android.os.SystemClock.elapsedRealtime()
                        if (gapMs >= 32L && wallMs - lastReport >= 700L) {
                            lastReport = wallMs
                            DebugLog.w("ChatJank", "frameGapMs=$gapMs firstVisible=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                        }
                    }
                    previousFrame = now
                }
            }
    }
    // 只在加载中且用户原本贴底时跟随；用户上滑后保留历史阅读位置。
    LaunchedEffect(conversation?.id, isStreaming) {
        snapshotFlow {
            val info = listState.layoutInfo
            val spacer = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
            val bottomGap = spacer?.let {
                info.viewportEndOffset - info.afterContentPadding - (it.offset + it.size)
            } ?: Int.MAX_VALUE
            Triple(listState.isScrollInProgress, listState.canScrollForward, bottomGap)
        }
            .distinctUntilChanged()
            .collect { (scrolling, canScrollForward, bottomGap) ->
            val atBottom = bottomGap.toFloat() <= followThresholdPx
            if (scrolling) {
                followBottom = atBottom
            } else if (isStreaming && followBottom && atBottom && canScrollForward) {
                listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
    }
    LaunchedEffect(conversation?.id, visibleMessages.size) {
        val session = conversation?.id.orEmpty()
        val count = visibleMessages.size
        val newSession = rememberedSession != session
        val appended = !newSession && count > rememberedCount
        if (count > 0 && (newSession || (appended && followBottom && !isStreaming))) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == count + 1 }
            listState.requestScrollToItem(count)
        }
        if (newSession) followBottom = true
        rememberedSession = session
        rememberedCount = count
    }
    val backgroundMode = assistant?.backgroundMode ?: "default"
    val backgroundPath = assistant?.backgroundPath
    val backgroundCss = assistant?.backgroundCss ?: ""
    val backgroundOpacity = assistant?.backgroundOpacity ?: 1f
    val backgroundBitmap = if (backgroundMode == "css") null
                           else rememberBackgroundBitmap(backgroundPath)
    val cssHtml = if (backgroundMode == "css" && backgroundCss.isNotBlank()) {
        "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<style>" +
            "html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden}" +
            backgroundCss + "\n" +
            "</style></head><body></body></html>"
    } else null

    val cssFallbackColors = remember(backgroundCss) {
        if (backgroundMode == "css" && backgroundCss.isNotBlank()) {
            parseCssFallbackColors(backgroundCss)
        } else null
    }

    val hasBackgroundLayer = cssHtml != null ||
        backgroundBitmap != null ||
        (backgroundMode == "image" && !backgroundPath.isNullOrBlank())

    LaunchedEffect(assistant?.id, assistant?.avatarPath, backgroundMode, backgroundPath, backgroundCss, cssHtml) {
        com.miniichatNext.carter.debug.DebugLog.i(
            "ChatBg",
            "assistant=${assistant?.id ?: "null"}/${assistant?.name ?: "-"} " +
                "avatar=${assistant?.avatar} avatarPath=${assistant?.avatarPath ?: "null"} " +
                "mode=$backgroundMode cssLen=${backgroundCss.length} " +
                "path=${backgroundPath ?: "null"} cssHtmlLen=${cssHtml?.length ?: 0}"
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // CSS模式：用WebView渲染CSS背景
        if (cssHtml != null) {
            cssFallbackColors?.let { colors ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (colors.size >= 2) {
                                androidx.compose.ui.graphics.Brush.linearGradient(colors)
                            } else {
                                androidx.compose.ui.graphics.SolidColor(colors.first())
                            }
                        )
                )
            }
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        runCatching {
                            settings.javaClass
                                .getMethod("setJavaScriptEnabled", Boolean::class.javaPrimitiveType!!)
                                .invoke(settings, false)
                        }
                        runCatching {
                            settings.javaClass
                                .getMethod("setSupportZoom", Boolean::class.javaPrimitiveType!!)
                                .invoke(settings, false)
                        }
                        runCatching {
                            settings.javaClass
                                .getMethod("setBuiltInZoomControls", Boolean::class.javaPrimitiveType!!)
                                .invoke(settings, false)
                        }
                        runCatching {
                            settings.javaClass
                                .getMethod("setDisplayZoomControls", Boolean::class.javaPrimitiveType!!)
                                .invoke(settings, false)
                        }
                        runCatching {
                            settings.javaClass
                                .getMethod("setForceDark", Int::class.javaPrimitiveType!!)
                                .invoke(settings, 0)
                        }
                        runCatching {
                            settings.javaClass
                                .getMethod("setAlgorithmicDarkeningAllowed", Boolean::class.javaPrimitiveType!!)
                                .invoke(settings, false)
                        }
                        isClickable = false
                        isFocusable = false
                        isLongClickable = false
                        // 不消费触摸：让事件继续冒泡到上层Compose Box的detectTapGestures，否则CSS背景层会吞掉双击空白进沉浸/单击退出沉浸的手势
                        // 本HTML是静态背景（overflow:hidden、无链接），不消费也不会有副作用
                        setOnTouchListener { _, _ -> false }
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        postDelayed({
                            com.miniichatNext.carter.debug.DebugLog.i(
                                "ChatBgWeb",
                                "webview state: attached=$isAttachedToWindow enabled=$isEnabled " +
                                    "size=${width}x${height} visibility=$visibility"
                            )
                        }, 800L)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                com.miniichatNext.carter.debug.DebugLog.i(
                                    "ChatBgWeb",
                                    "background page finished: $url " +
                                        "size=${view?.width ?: 0}x${view?.height ?: 0}"
                                )
                            }

                            override fun onReceivedError(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                val mainFrame = request?.isForMainFrame ?: true
                                com.miniichatNext.carter.debug.DebugLog.e(
                                    "ChatBgWeb",
                                    "background load error: mainFrame=$mainFrame " +
                                        "code=${error?.errorCode} ${error?.description}"
                                )
                                if (mainFrame) {
                                    view?.visibility = android.view.View.INVISIBLE
                                }
                            }
                        }
                        tag = cssHtml
                        com.miniichatNext.carter.debug.DebugLog.d(
                            "ChatBgWeb",
                            "loading html(${cssHtml.length} chars): " + cssHtml.take(800).replace("\n", " ")
                        )
                        loadDataWithBaseURL(null, cssHtml, "text/html", "utf-8", null)
                    }
                },
                update = { wv ->

                    if (wv.tag != cssHtml) {
                        wv.tag = cssHtml
                        wv.loadDataWithBaseURL(null, cssHtml, "text/html", "utf-8", null)
                    }
                },
                onRelease = { wv ->
                    // 离开组合时销毁WebView，避免泄漏
                    wv.stopLoading()
                    wv.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (backgroundBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = backgroundBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f - backgroundOpacity))
            )
        }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // CSS模式下同样要把聊天内容的背景设为透明，让WebView的CSS透出来
            .background(
                MaterialTheme.colorScheme.background.copy(
                    alpha = if (hasBackgroundLayer && !addPanelOpen && !candidatesOpen) 0f else 1f
                )
            )
            .imePadding()
    ) {
        ChatTopBar(
            title = conversation?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.app_name),
            onMenu = onMenu,
            onEditTitle = {
                renameDraft = conversation?.title ?: ""
                showRenameDialog = true
            },
            onNew = onNew,
            transparent = false
        )

        // 消息区+面板的点空白处关闭层
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (visibleMessages.isEmpty()) {
            EmptyState(
                onPick = { onSend(it, emptyList()) },
                onOpenSettings = onOpenSettings,
                showSettingsHint = activeProvider == null,
                assistantAvatar = com.miniichatNext.carter.data.avatar.Avatar.fromLegacy(
                    assistant?.avatar ?: "🤖", assistant?.avatarPath
                ),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(
                    items = visibleMessages,
                    key = { it.id },
                    contentType = { if (it.role == "assistant") "assistant" else "user" },
                ) { msg ->
                    ChatMessageListItem(
                        message = msg,
                        isLastAssistant = msg.id == visibleMessages.lastOrNull()?.id && msg.role == "assistant",
                        isStreaming = isStreaming && msg.id == visibleMessages.lastOrNull()?.id,
                        streamingOverlay = streamingOverlay,
                        userAvatar = userProfile.avatar,
                        assistantAvatar = assistantDisplayAvatar,
                        senderLabel = if (msg.role == "user") userSenderLabel else assistantSenderLabel,
                        onStartEdit = {
                            editingMessageId = msg.id
                            input = msg.content
                            focusInputRequest++
                        },
                        onContinue = onContinue,
                        onDelete = onDeleteMessage,
                        onRegenerateFrom = { retellTargetId = it },
                        onApproveTool = onApproveTool,
                        onRejectTool = onRejectTool,
                        onFullscreen = onFullscreen,
                    )
                }
                item(key = "chat-bottom-spacer") { Spacer(Modifier.height(1.dp)) }
            }
            }
        }

        // 面板打开时：点一下/滑一下消息区即收起（面板内、输入框内的点击不受影响；不消费事件，所以消息列表照常能滚动）
        if (addPanelOpen || candidatesOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(addPanelOpen, candidatesOpen) {
                        var fired = false
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (fired) continue
                                if (event.changes.any {
                                        it.positionChanged() || it.changedToUp()
                                    }
                                ) {
                                    fired = true
                                    addPanelOpen = false
                                    candidatesOpen = false
                                }
                            }
                        }
                    }
            )
        }

        // AI调用需要「所有文件访问权限」的工具但用户未授权→弹窗引导去授权
        val pendingPermission = vm?.pendingManageStorage?.collectAsState()?.value
        val permCtx = androidx.compose.ui.platform.LocalContext.current
        pendingPermission?.let { tool ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.dismissPermissionRequest() },
                title = { Text(stringResource(com.miniichatNext.carter.R.string.permission_manage_storage_title)) },
                text = { Text(stringResource(com.miniichatNext.carter.R.string.permission_manage_storage_message, tool)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.dismissPermissionRequest()
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + permCtx.packageName)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        permCtx.startActivity(intent)
                    }) { Text(stringResource(com.miniichatNext.carter.R.string.permission_grant)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissPermissionRequest() }) {
                        Text(stringResource(com.miniichatNext.carter.R.string.cancel))
                    }
                },
            )
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            editing = editingMessageId != null,
            onCommitEdit = { text ->
                editingMessageId?.let { id -> onEditMessage(id, text) }
                editingMessageId = null
                input = ""
            },
            onCancelEdit = {
                editingMessageId = null
                input = ""
            },
            attachments = pendingAttachments,
            onAttachmentsChange = { pendingAttachments = it },
            onPickModel = onPickModel,
            skills = skills,
            enabledSkillIds = effectiveSkillIds,
            onToggleSkill = onToggleSkill,
            onContextCompress = onContextCompress,
            workspaces = workspaces,
            effectiveWorkspaceId = effectiveWorkspaceId,
            onPickWorkspace = onPickWorkspace,
            mcpServers = mcpServers,
            effectiveMcpServerIds = effectiveMcpServerIds,
            onToggleMcpServer = onToggleMcpServer,
            onResetToolOverrides = onResetToolOverrides,
            onOpenTerminal = onOpenTerminal,
            onManageSkills = onManageSkills,
            onManageWorkspaces = onManageWorkspaces,
            onManageMcp = onManageMcp,
            onSend = {
                val text = input
                val atts = pendingAttachments
                input = ""
                pendingAttachments = emptyList()
                candidatesOpen = false
                addPanelOpen = false
                onSend(text, atts)
            },
            onStop = onStop,
            isStreaming = isStreaming,
            enabled = activeProvider != null && effectiveModelId.isNotBlank(),
            // 模型选择按钮独立于"已选模型"状态：恒可点。未选/未配置时点进去
            // 由AppRoot分流（有提供商→选择器，无提供商→添加提供商），
            // 绝不能再被activeModel/activeProvider为空连带置灰（否则死循环）
            canPickModel = true,
            // 同上：底栏保持自身背景，不被自定义背景影响
            transparent = false,
            addPanelOpen = addPanelOpen,
            onToggleAddPanel = {
                val next = !addPanelOpen
                addPanelOpen = next
                if (next) candidatesOpen = false
            },
            candidatesOpen = candidatesOpen,
            onToggleCandidates = {
                val next = !candidatesOpen
                if (next) suggestionPage = 0
                candidatesOpen = next
                if (next) addPanelOpen = false
            },
            candidatesPanel = {
                val anchorMsgId = conversation?.messages?.lastOrNull { it.role == "assistant" }?.id.orEmpty()
                val cacheKey = suggestionCacheKey(anchorMsgId, suggestionPage)
                SuggestionPanel(
                    conversation = conversation,
                    anchorMessageId = anchorMsgId,
                    page = suggestionPage,
                    loading = anchorMsgId.isNotEmpty() && cacheKey in suggestionsGenerating,
                    onPageChange = { suggestionPage = it },
                    onGenerate = { manual -> onSuggestions(anchorMsgId, suggestionPage, manual) },
                    onPick = { candidate ->
                        candidatesOpen = false
                        onSend(candidate, emptyList())
                    },
                    onEdit = { candidate ->
                        // 把候选内容放进输入框，收起面板并聚焦输入框（自动弹键盘）
                        input = candidate
                        candidatesOpen = false
                        addPanelOpen = false
                        focusInputRequest++
                    }
                )
            },
            onDismissPanels = {
                addPanelOpen = false
                candidatesOpen = false
            },
            focusInputRequest = focusInputRequest
        )
    }

    }

    // Rename dialog
    if (showRenameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onRenameChat(renameDraft.ifBlank { "" })
                    showRenameDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重说确认弹窗：确认后从该消息处截断并重新生成
    retellTargetId?.let { targetId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { retellTargetId = null },
            title = { Text(stringResource(R.string.retell_confirm_title)) },
            text = { com.miniichatNext.carter.ui.markdown.MarkdownText(text = stringResource(R.string.retell_confirm_message).replace("\n", "\n")) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    retellTargetId = null
                    onRegenerateFrom(targetId)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { retellTargetId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

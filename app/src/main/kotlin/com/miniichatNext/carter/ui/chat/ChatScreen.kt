package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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

@Composable
fun ChatScreen(
    conversation: Conversation?,
    settings: AppSettings,
    activeProvider: ProviderConfig?,
    isStreaming: Boolean,
    streamingOverlay: Pair<String, String>? = null,
    assistant: com.miniichatNext.carter.data.model.Assistant? = null,
    userProfile: com.miniichatNext.carter.data.model.UserProfile = com.miniichatNext.carter.data.model.UserProfile(),
    assistants: List<com.miniichatNext.carter.data.model.Assistant> = emptyList(),
    skills: List<com.miniichatNext.carter.data.skills.Skill> = emptyList(),
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onContextCompress: () -> Unit = {},
    onSuggestions: (Int) -> Unit = {},
    suggestionsGenerating: Set<Int> = emptySet(),
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
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<com.miniichatNext.carter.data.model.Attachment>>(emptyList()) }
    val listState = rememberLazyListState()
    val rawMessages = conversation?.messages ?: emptyList()
    val messages = remember(rawMessages, streamingOverlay) {
        val ov = streamingOverlay
        if (ov == null) rawMessages
        else rawMessages.map { if (it.id == ov.first) it.copy(content = ov.second) else it }
    }
    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDraft by rememberSaveable { mutableStateOf("") }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    // 两个内嵌面板：+ 面板 与 候选回复面板（互斥，点空白处统一收起）
    var addPanelOpen by rememberSaveable { mutableStateOf(false) }
    var candidatesOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionPage by rememberSaveable { mutableStateOf(0) }
    // 递增即请求一次"聚焦输入框 + 弹键盘"（候选回复点"编辑"时用）
    var focusInputRequest by remember { mutableStateOf(0) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val immersiveAlpha by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        animationSpec = tween(durationMillis = 320),
        label = "immersive-alpha"
    )

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val backgroundMode = assistant?.backgroundMode ?: "default"
    val backgroundPath = assistant?.backgroundPath
    val backgroundCss = assistant?.backgroundCss ?: ""
    val backgroundOpacity = assistant?.backgroundOpacity ?: 1f
    // 图片模式：渲染图片
    // CSS模式：直接渲染WebView背景层
    // default：无背景
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { immersive = true },
                    onTap = { immersive = false }
                )
            }
    ) {
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
            .background(MaterialTheme.colorScheme.background.copy(alpha = if (hasBackgroundLayer) 0f else 1f))
            .imePadding()
            .graphicsLayer { alpha = immersiveAlpha }
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
        if (messages.isEmpty()) {
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
                items(messages, key = { it.id }) { msg ->
                    val isUserMsg = msg.role == "user"
                    MessageItem(
                        message = msg,
                        // 消息行头像
                        avatar = if (isUserMsg) userProfile.avatar
                        else com.miniichatNext.carter.data.avatar.Avatar.fromLegacy(
                            assistant?.avatar ?: "🤖", assistant?.avatarPath
                        ),
                        senderLabel = if (msg.role == "user") stringResource(R.string.profile_default_name)
                        else settings.activeModel.ifBlank { activeProvider?.name ?: stringResource(R.string.assistant) },
                        isLastAssistant = msg.id == messages.lastOrNull()?.id && msg.role == "assistant",
                        isStreaming = isStreaming,
                        editing = editingMessageId == msg.id,
                        editingDraft = if (editingMessageId == msg.id) editingDraft else "",
                        onEditingDraftChange = { editingDraft = it },
                        onStartEdit = {
                            editingMessageId = msg.id
                            editingDraft = msg.content
                        },
                        onCommitEdit = {
                            editingMessageId?.let { id ->
                                onEditMessage(id, editingDraft)
                            }
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onCancelEdit = {
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onContinue = onContinue,
                        onDelete = { onDeleteMessage(msg.id) },
                        onRegenerateFrom = { onRegenerateFrom(msg.id) }
                    )
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
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            attachments = pendingAttachments,
            onAttachmentsChange = { pendingAttachments = it },
            modelLabel = settings.activeModel.ifBlank { stringResource(R.string.select_model) },
            onPickModel = onPickModel,
            skills = skills,
            enabledSkillIds = effectiveSkillIds,
            onToggleSkill = onToggleSkill,
            onContextCompress = onContextCompress,
            onSend = {
                val text = input
                val atts = pendingAttachments
                input = ""
                pendingAttachments = emptyList()
                // 发送时收起面板
                candidatesOpen = false
                addPanelOpen = false
                onSend(text, atts)
            },
            onStop = onStop,
            isStreaming = isStreaming,
            enabled = activeProvider != null && settings.activeModel.isNotBlank(),
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
                candidatesOpen = next
                if (next) {
                    addPanelOpen = false
                    suggestionPage = 0
                }
            },
            candidatesPanel = {
                SuggestionPanel(
                    conversation = conversation,
                    page = suggestionPage,
                    loading = suggestionPage in suggestionsGenerating,
                    onPageChange = { suggestionPage = it },
                    onGenerate = { onSuggestions(it) },
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

    // 双击空白区域以隐藏界面，仅显示背景，这样可以更好的观看老婆😆，这段注释就当做彩蛋2
    if (immersive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures { immersive = false }
                }
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

    
}

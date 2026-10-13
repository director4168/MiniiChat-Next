package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.ui.markdown.MarkdownText
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.ui.components.AvatarView

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AssistantRow(
    message: Message,
    avatar: com.miniichatNext.carter.data.avatar.Avatar,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    onStartEdit: () -> Unit = {},
    onContinue: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit = {},
    onApproveTool: ((com.miniichatNext.carter.data.model.ToolInvocation, String?) -> Unit)? = null,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
    onRejectTool: ((com.miniichatNext.carter.data.model.ToolInvocation) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 助手消息头像：渲染真实助手头像
            com.miniichatNext.carter.ui.components.AvatarView(
                avatar = avatar,
                fallbackInitial = "M",
                size = 22.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                senderLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(8.dp))
        // 渲染条件：正文/附件/节点化parts/思考/工具调用任一存在都要渲染
        // 纯思考+工具调用场景content为空但orderedParts有Reasoning/Tool段，审批按钮也在这里面（修"AI不回复"）
        if (message.content.isNotEmpty() || message.attachments.isNotEmpty()
            || message.orderedParts.isNotEmpty()
            || message.reasoningContent?.isNotBlank() == true
            || message.toolInvocations.isNotEmpty()
        ) {
            // 新消息（orderedParts非空）：扫描+分组渲染
            // 连续的Reasoning/Tool段→包成一张ChainOfThought卡片（带collapse+时间线）
            // Text段→文本气泡
            // 旧消息（orderedParts空）：保留原有COT卡片在上的布局，避免改动历史体验
            if (message.orderedParts.isNotEmpty()) {
                val toolById = message.toolInvocations.associateBy { it.callId }
                // callId去重：数据层任何时候出现重复Tool段，只有首个渲染成工具卡，其余跳过
                val seenCallIds = mutableSetOf<String>()
                val lastTextIdx = message.orderedParts.indexOfLast { it is com.miniichatNext.carter.data.model.MessageSegment.Text }
                val parts = message.orderedParts
                var i = 0
                while (i < parts.size) {
                    val seg = parts[i]
                    if (seg is com.miniichatNext.carter.data.model.MessageSegment.Text) {
                        if (seg.text.isNotBlank()) {
                            TextBubble(
                                text = seg.text,
                                showMenu = i == lastTextIdx,
                                onLongClick = onLongPress,
                                onCopy = { clipboard.setText(AnnotatedString(seg.text)) },
                                onFullscreen = onFullscreen,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        i++
                    } else {
                        // 收集连续的Reasoning/Tool段（不夹Text）
                        val clusterStart = i
                        while (i < parts.size && parts[i] !is com.miniichatNext.carter.data.model.MessageSegment.Text) i++
                        val cluster = parts.subList(clusterStart, i)
                        // 拆成(思考/澄清)和(工具/批)两组，分别渲染
                        val cotSteps = buildList<ThoughtStep> {
                            cluster.forEach { s ->
                                when (s) {
                                    is com.miniichatNext.carter.data.model.MessageSegment.Reasoning -> {
                                        if (s.text.isNotBlank()) add(ThoughtStep.Reasoning(s.text))
                                    }
                                    is com.miniichatNext.carter.data.model.MessageSegment.Tool -> {
                                        if (s.callId in seenCallIds) return@buildList
                                        seenCallIds.add(s.callId)
                                        val inv = toolById[s.callId]
                                        if (inv != null && !isClarificationTool(inv.toolName)) {
                                            add(ThoughtStep.Tool(inv))
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                        if (cotSteps.isNotEmpty()) {
                            ChainOfThought(
                                steps = cotSteps,
                                onApprove = onApproveTool,
                                onReject = onRejectTool
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        // 澄清工具卡片（独立于COT卡片，直接渲染）
                        cluster.forEach { s ->
                            if (s is com.miniichatNext.carter.data.model.MessageSegment.Tool) {
                                val inv = toolById[s.callId]
                                if (inv != null && isClarificationTool(inv.toolName)) {
                                    InteractiveClarificationCard(
                                        invocation = inv,
                                        onApprove = onApproveTool
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                // Legacy布局（历史消息）：思考+全部工具调用在上，content在下
                val clarifications = message.toolInvocations.filter { isClarificationTool(it.toolName) }
                val steps = buildList {
                    message.reasoningContent?.takeIf { it.isNotBlank() }
                        ?.let { add(ThoughtStep.Reasoning(it)) }
                    message.toolInvocations
                        .filterNot { isClarificationTool(it.toolName) }
                        .forEach { add(ThoughtStep.Tool(it)) }
                }
                if (steps.isNotEmpty()) {
                    ChainOfThought(
                        steps = steps,
                        onApprove = onApproveTool,
                        onReject = onRejectTool
                    )
                    Spacer(Modifier.height(8.dp))
                }
                clarifications.forEach { inv ->
                    InteractiveClarificationCard(invocation = inv, onApprove = onApproveTool)
                    Spacer(Modifier.height(8.dp))
                }
                if (message.content.isNotEmpty()) {
                    TextBubble(
                        text = message.content,
                        showMenu = true,
                        onLongClick = onLongPress,
                        onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                        onFullscreen = onFullscreen,
                    )
                }
            }
            }
            if (isLastAssistant && isStreaming) {
                TypingDots()
            }
        }
        // 只有最后一条助手的消息会显示继续和编辑按钮
        if (message.content.isNotEmpty() && !isStreaming) {
            Spacer(Modifier.height(8.dp))
            AssistantActionBar(
                onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                onContinue = if (isLastAssistant) onContinue else null,
                onEdit = if (isLastAssistant) onStartEdit else null
            )
        }
    }

@Composable
internal fun AssistantActionBar(
    onCopy: () -> Unit,
    onContinue: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(Icons.Default.ContentCopy, stringResource(R.string.copy), onCopy)
        if (onContinue != null) {
            ActionChip(Icons.Default.PlayArrow, stringResource(R.string.continue_generating), onContinue)
        }
        if (onEdit != null) {
            ActionChip(Icons.Default.Edit, stringResource(R.string.edit), onEdit)
        }
    }
}

@Composable
internal fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp),
            tint = Color(0xFFECECEC))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = Color(0xFFECECEC))
    }
}

@Composable
internal fun TypingDots() {
    val infinite = rememberInfiniteTransition(label = "typing")
    val alpha by infinite.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (i == 0) alpha
                            else if (i == 1) (1f - alpha)
                            else alpha * 0.7f + 0.3f
                        )
                    )
            )
        }
    }
}


// 节点化渲染用的三个内联组件

/** 单段文本气泡（带可选长按菜单） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(
    text: String,
    showMenu: Boolean,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onFullscreen: ((String, Boolean) -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ))
            .background(Color(0xFF1A1A1A))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        SelectionContainer {
            Column {
                MarkdownText(
                    text = text,
                    color = Color(0xFFECECEC),
                    modifier = Modifier.fillMaxWidth(),
                    onFullscreen = onFullscreen,
                )
            }
        }
    }
}

/**
 * 步骤容器：左侧24dp图标槽+右侧内容，与时间线对齐
 * （原ChainOfThought.kt内私有函数，照搬）
 */
@Composable
private fun StepRow(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) { icon() }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
            content()
        }
    }
}

/**
 * 思考过程（瘦版时间线，与ChainOfThought.ReasoningStep同款）
 */
@Composable
private fun ReasoningBlock(text: String) {
    var expand by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    StepRow(
        icon = {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expand = !expand },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tool_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = if (expand) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        if (expand) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
            )
        } else {
            Text(
                text = text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 工具步骤：与ChainOfThought.ToolStep同款视觉，但作为inline节点独立出现
 * （无外层Card包裹）
 */
@Composable
private fun InlineToolStep(
    invocation: com.miniichatNext.carter.data.model.ToolInvocation,
    onApprove: ((com.miniichatNext.carter.data.model.ToolInvocation, String?) -> Unit)?,
    onReject: ((com.miniichatNext.carter.data.model.ToolInvocation) -> Unit)?,
) {
    var expand by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val isPending = invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.PENDING_APPROVAL
    val isRunning = invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.EXECUTING ||
        invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.RUNNING
    val isSuccess = invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.SUCCESS
    val isError = invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.ERROR
    val isRejected = invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.REJECTED

    val statusColor = when {
        isPending -> MaterialTheme.colorScheme.tertiary
        isRunning -> MaterialTheme.colorScheme.primary
        isSuccess -> Color(0xFF2E7D32)
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        isPending -> stringResource(R.string.tool_status_pending)
        isRunning -> stringResource(R.string.tool_status_running)
        isSuccess -> stringResource(R.string.tool_status_success)
        isError -> stringResource(R.string.tool_status_error)
        isRejected -> stringResource(R.string.tool_status_rejected)
        invocation.state == com.miniichatNext.carter.data.model.ToolInvocationState.CANCELLED ->
            stringResource(R.string.tool_status_cancelled)
        else -> invocation.state
    }

    StepRow(
        icon = {
            when {
                isRunning -> {
                    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "inline_tool_running")
                    val rotation by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(durationMillis = 1100, easing = androidx.compose.animation.core.LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                        ),
                        label = "spin"
                    )
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).rotate(rotation),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                }
                isSuccess -> Icon(
                    Icons.Default.CheckCircle, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = statusColor
                )
                isError -> Icon(
                    Icons.Default.Warning, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = statusColor
                )
                isRejected -> Icon(
                    Icons.Default.Close, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = statusColor
                )
                else -> Icon(
                    Icons.Default.Build, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = statusColor
                )
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expand = !expand },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tool_call_prefix, invocation.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
            Icon(
                imageVector = if (expand) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        if (expand) {
            val noArgsLabel = stringResource(R.string.tool_no_args)
            Spacer(Modifier.height(6.dp))
            StepCodeBlock(stringResource(R.string.tool_args_label), invocation.arguments.ifBlank { noArgsLabel })
            Spacer(Modifier.height(6.dp))
            // 老版本：单个"调用结果"块，错误信息拼到text里（"错误：xxx"）
            StepCodeBlock(
                label = stringResource(R.string.tool_result_label),
                text = when {
                    isRunning -> stringResource(R.string.tool_result_running)
                    invocation.error != null -> stringResource(R.string.tool_result_error, invocation.error)
                    invocation.resultOutput.isNotBlank() -> invocation.resultOutput
                    isPending -> stringResource(R.string.tool_result_pending)
                    else -> stringResource(R.string.tool_result_none)
                }
            )
        }
        // PENDING审批按钮：行尾对齐（EndArrangement.End），紧凑按钮（30dp高）
        if (isPending && (onApprove != null || onReject != null)) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (onReject != null) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onReject(invocation) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 0.dp
                        )
                    ) { Text(stringResource(R.string.tool_reject), fontSize = 12.sp) }
                }
                Spacer(Modifier.width(8.dp))
                if (onApprove != null) {
                    androidx.compose.material3.Button(
                        onClick = { onApprove(invocation, null) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp, vertical = 0.dp
                        )
                    ) {
                        Text(stringResource(R.string.tool_approve), fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 代码块：左侧行号栏（固定）+竖分割线+右侧代码内容（横滚/纵滚）
 * （原ChainOfThought.kt内私有函数CodeBlock，照搬）
 */
@Composable
private fun StepCodeBlock(label: String, text: String) {
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    val lines = androidx.compose.runtime.remember(text) { text.split('\n') }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .heightIn(max = 220.dp)
                .padding(vertical = 8.dp)
        ) {
            val gutterDivider = MaterialTheme.colorScheme.outlineVariant
            val gutterFg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            Column(
                modifier = Modifier
                    .drawBehind {
                        val x = size.width - 0.5.dp.toPx()
                        drawLine(
                            color = gutterDivider,
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(start = 8.dp, end = 6.dp)
            ) {
                lines.forEachIndexed { index, _ ->
                    Text(
                        text = "${index + 1}",
                        style = codeStyle,
                        color = gutterFg,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp)
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

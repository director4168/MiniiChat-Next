package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.model.ToolInvocationState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一条思考步骤：模型推理 或 一次工具调用 */
sealed interface ThoughtStep {
    data class Reasoning(val text: String) : ThoughtStep
    data class Tool(val invocation: ToolInvocation) : ThoughtStep
}

private const val CLARIFICATION_TOOL = "interactive_clarification"

/**
 * 以时间线卡片的形式展示一组思考步骤（对照RikkaHub的ChainOfThought）。
 * 步骤数超过 [collapsedVisibleCount] 时默认只显示末尾若干步，顶部提供展开/收起。
 */
@Composable
fun ChainOfThought(
    steps: List<ThoughtStep>,
    modifier: Modifier = Modifier,
    collapsedVisibleCount: Int = 2,
    onApprove: ((ToolInvocation, String?) -> Unit)? = null,
    onReject: ((ToolInvocation) -> Unit)? = null,
) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val visibleSteps = if (expanded || !canCollapse) steps else steps.takeLast(collapsedVisibleCount)
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .animateContentSize()
        ) {
            if (canCollapse) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = if (expanded) "收起" else "展开其余 ${steps.size - collapsedVisibleCount} 步",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Box(
                modifier = Modifier.drawBehind {
                    val x = 12.dp.toPx()
                    val pad = 18.dp.toPx()
                    if (size.height > pad * 2) {
                        drawLine(
                            color = lineColor,
                            start = Offset(x, pad),
                            end = Offset(x, size.height - pad),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            ) {
                Column {
                    visibleSteps.forEach { step ->
                        when (step) {
                            is ThoughtStep.Reasoning -> ReasoningStep(step.text)
                            is ThoughtStep.Tool -> ToolStep(
                                invocation = step.invocation,
                                onApprove = onApprove,
                                onReject = onReject
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 步骤容器：左侧24dp图标槽 + 右侧内容，与时间线对齐 */
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

@Composable
private fun ReasoningStep(text: String) {
    var expand by remember { mutableStateOf(false) }
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
                text = "思考过程",
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
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
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

@Composable
private fun ToolStep(
    invocation: ToolInvocation,
    onApprove: ((ToolInvocation, String?) -> Unit)?,
    onReject: ((ToolInvocation) -> Unit)?,
) {
    var expand by remember { mutableStateOf(false) }
    val isPending = invocation.state == ToolInvocationState.PENDING_APPROVAL
    val isRunning = invocation.state == ToolInvocationState.EXECUTING
    val isSuccess = invocation.state == ToolInvocationState.SUCCESS
    val isError = invocation.state == ToolInvocationState.ERROR
    val isRejected = invocation.state == ToolInvocationState.REJECTED

    val statusColor = when {
        isPending -> MaterialTheme.colorScheme.tertiary
        isRunning -> MaterialTheme.colorScheme.primary
        isSuccess -> Color(0xFF2E7D32)
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        isPending -> "等待审批"
        isRunning -> "执行中"
        isSuccess -> "调用完成"
        isError -> "调用出错"
        isRejected -> "已拒绝"
        else -> invocation.state
    }

    StepRow(
        icon = {
            when {
                isRunning -> {
                    val transition = rememberInfiniteTransition(label = "tool_running_anim")
                    val rotation by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1100, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "spin"
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).rotate(rotation),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                }
                isSuccess -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                isError -> Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                isRejected -> Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                else -> Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
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
                text = "调用工具 ${invocation.toolName}",
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
            Spacer(Modifier.height(6.dp))
            CodeBlock("输入参数", invocation.arguments.ifBlank { "（无参数）" })
            Spacer(Modifier.height(6.dp))
            CodeBlock(
                label = "调用结果",
                text = when {
                    isRunning -> "执行中…"
                    invocation.error != null -> "错误：${invocation.error}"
                    invocation.result.isNotBlank() -> invocation.result
                    isPending -> "等待用户授权…"
                    else -> "（无返回结果）"
                }
            )
        }

        if (isPending && (onApprove != null || onReject != null)) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (onReject != null) {
                    OutlinedButton(
                        onClick = { onReject(invocation) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("拒绝", fontSize = 12.sp) }
                }
                Spacer(Modifier.width(8.dp))
                if (onApprove != null) {
                    Button(
                        onClick = { onApprove(invocation, null) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) { Text("同意调用", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    val lines = remember(text) { text.split('\n') }
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
                .heightIn(max = 220.dp)
                .padding(vertical = 8.dp)
        ) {
            // 行号栏（固定在左侧，代码横向滚动）
            // 注意：颜色必须在组合期取出来，drawBehind的lambda不是 @Composable
            val gutterDivider = MaterialTheme.colorScheme.outlineVariant
            val gutterFg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            Column(
                modifier = Modifier
                    .drawBehind {
                        val x = size.width - 0.5.dp.toPx()
                        drawLine(
                            color = gutterDivider,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
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
                        textAlign = TextAlign.End
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
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

/**
 * 需求澄清：问题 + 最多4个选项（可多选）+ 自定义输入 + 提交按钮。
 * 需要用户填表，因此不放进ChainOfThought的步骤流里。
 */
@Composable
fun InteractiveClarificationCard(
    invocation: ToolInvocation,
    onApprove: ((ToolInvocation, String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val json = remember { Json { ignoreUnknownKeys = true } }
    val parsed = remember(invocation.arguments) {
        runCatching {
            val obj = json.parseToJsonElement(invocation.arguments).jsonObject
            val q = obj["question"]?.jsonPrimitive?.contentOrNull ?: "请确认您的需求选项"
            val rawOpts = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val allowMulti = obj["allow_multiple"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            Triple(q, rawOpts.take(4), allowMulti)
        }.getOrDefault(Triple("请选择需求方案", listOf("默认实现方案"), false))
    }

    val question = parsed.first
    val options = parsed.second
    val allowMultiple = parsed.third

    val selectedOptions = remember { mutableStateListOf<String>() }
    var singleSelected by remember { mutableStateOf<String?>(options.firstOrNull()) }
    var customInput by remember { mutableStateOf("") }
    var isSubmitted by remember { mutableStateOf(invocation.result.isNotBlank()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (allowMultiple) "支持多选，可在末尾手动输入自定义需求" else "请单选，可在末尾手动输入自定义需求",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            options.forEachIndexed { index, optionText ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isSubmitted) {
                            if (allowMultiple) {
                                if (optionText in selectedOptions) selectedOptions.remove(optionText)
                                else selectedOptions.add(optionText)
                            } else {
                                singleSelected = optionText
                            }
                        }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (allowMultiple) {
                        Checkbox(
                            checked = optionText in selectedOptions,
                            onCheckedChange = { checked ->
                                if (!isSubmitted) {
                                    if (checked) selectedOptions.add(optionText) else selectedOptions.remove(optionText)
                                }
                            }
                        )
                    } else {
                        RadioButton(
                            selected = singleSelected == optionText,
                            onClick = { if (!isSubmitted) singleSelected = optionText }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "选项 ${index + 1}: $optionText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = customInput,
                onValueChange = { if (!isSubmitted) customInput = it },
                label = { Text("补充选项：手动输入自定义需求（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSubmitted
            )

            Spacer(Modifier.height(14.dp))

            if (!isSubmitted) {
                Button(
                    onClick = {
                        isSubmitted = true
                        val selectedList = if (allowMultiple) selectedOptions.toList() else listOfNotNull(singleSelected)
                        val extra = customInput.trim()
                        val summary = buildString {
                            append("用户已确认选项：")
                            append(if (selectedList.isNotEmpty()) selectedList.joinToString("、") else "未勾选预设选项")
                            if (extra.isNotBlank()) append("；自定义补充需求：").append(extra)
                        }
                        onApprove?.invoke(invocation, summary)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("提交选择并继续", fontWeight = FontWeight.Bold) }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (invocation.result.isNotBlank()) invocation.result else "需求选项已确认",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }
    }
}

/** 是否为需要用户填表的交互式工具 */
fun isClarificationTool(name: String): Boolean = name == CLARIFICATION_TOOL

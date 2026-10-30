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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.tools.ClarificationParser
import com.miniichatNext.carter.data.tools.ClarificationQuestion
import com.miniichatNext.carter.data.model.ToolInvocation
import com.miniichatNext.carter.data.model.ToolInvocationState

/** 一条思考步骤：模型推理或一次工具调用 */
sealed interface ThoughtStep {
    data class Reasoning(val text: String) : ThoughtStep
    data class Tool(val invocation: ToolInvocation) : ThoughtStep
}

private const val CLARIFICATION_TOOL = "interactive_clarification"

/**
 * 以时间线卡片的形式展示一组思考步骤（对照RikkaHub的ChainOfThought）。
 * 步骤数超过[collapsedVisibleCount]时默认只显示末尾若干步，顶部提供展开/收起。
 */
@Composable
fun ChainOfThought(
    steps: List<ThoughtStep>,
    modifier: Modifier = Modifier,
    collapsedVisibleCount: Int = 2,
    animateSize: Boolean = true,
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
                .then(if (animateSize) Modifier.animateContentSize() else Modifier)
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
                        text = if (expanded) stringResource(R.string.tool_collapse)
                        else stringResource(R.string.tool_expand_more, steps.size - collapsedVisibleCount),
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
                    val startIndex = steps.size - visibleSteps.size
                    visibleSteps.forEachIndexed { offset, step ->
                        val stepKey = when (step) {
                            is ThoughtStep.Reasoning -> "reasoning:${startIndex + offset}"
                            is ThoughtStep.Tool -> "tool:${step.invocation.callId}"
                        }
                        key(stepKey) {
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
}

/** 步骤容器：左侧24dp图标槽+右侧内容，与时间线对齐 */
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
        isPending -> stringResource(R.string.tool_status_pending)
        isRunning -> stringResource(R.string.tool_status_running)
        isSuccess -> stringResource(R.string.tool_status_success)
        isError -> stringResource(R.string.tool_status_error)
        isRejected -> stringResource(R.string.tool_status_rejected)
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
            CodeBlock(stringResource(R.string.tool_args_label), invocation.arguments.ifBlank { noArgsLabel })
            Spacer(Modifier.height(6.dp))
            CodeBlock(
                label = stringResource(R.string.tool_result_label),
                text = when {
                    isRunning -> stringResource(R.string.tool_result_running)
                    invocation.error != null -> stringResource(R.string.tool_result_error, invocation.error)
                    invocation.result.isNotBlank() -> invocation.result
                    isPending -> stringResource(R.string.tool_result_pending)
                    else -> stringResource(R.string.tool_result_none)
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
                    ) { Text(stringResource(R.string.tool_reject), fontSize = 12.sp) }
                }
                Spacer(Modifier.width(8.dp))
                if (onApprove != null) {
                    Button(
                        onClick = { onApprove(invocation, null) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) { Text(stringResource(R.string.tool_approve), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
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
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            // 行号栏（固定在左侧，代码横向滚动）
            // 注意：颜色必须在组合期取出来，drawBehind的lambda不是@Composable
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
                    .padding(start = 8.dp, end = 8.dp)
            ) {
                lines.forEach { line ->
                    Text(
                        text = line.ifEmpty { " " },
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        softWrap = true,
                    )
                }
            }
        }
    }
}

/**
 * 需求澄清：一次最多16道问题（每题最多4个选项，可多选）+自定义输入+统一提交。
 * 未回答时展示完整表单；全部回答后自动折叠（与其他工具调用一致），
 * 收起时只显示问题与回答内容，展开可看每道题的选项详情。
 */
@Composable
fun InteractiveClarificationCard(
    invocation: ToolInvocation,
    onApprove: ((ToolInvocation, String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val questions = remember(invocation.arguments) {
        val parsed = ClarificationParser.parse(invocation.arguments)
        parsed.ifEmpty {
            listOf(
                ClarificationQuestion(
                    question = context.getString(R.string.clarify_fallback_title),
                    options = listOf(context.getString(R.string.clarify_fallback_option)),
                    allowMultiple = false,
                )
            )
        }
    }
    val answered = invocation.state == ToolInvocationState.SUCCESS && invocation.result.isNotBlank()
    var expanded by remember { mutableStateOf(false) }
    var submittedSummary by remember { mutableStateOf<String?>(null) }
    val answers = remember(questions.size) { ClarifyAnswerState(questions.size) }

    val summaryPrefix = stringResource(R.string.clarify_summary_prefix)
    val summaryNone = stringResource(R.string.clarify_summary_none)
    val summaryCustom = stringResource(R.string.clarify_summary_custom)
    val questionTemplate = stringResource(R.string.clarify_summary_question)
    val listSeparator = stringResource(R.string.common_enum_separator)
    val multiHint = stringResource(R.string.clarify_multi_hint)
    val singleHint = stringResource(R.string.clarify_single_hint)
    val customLabel = stringResource(R.string.clarify_custom_label)
    val optionLine = stringResource(R.string.clarify_option_line)
    val summaryText = submittedSummary ?: invocation.result

    // 本地状态优先；进程重启后本地选择丢失，就从已提交的结果文本里按题回推
    val summaryLines = remember(summaryText) { summaryText.split('\n') }
    fun pickedFor(index: Int, question: ClarificationQuestion): List<String> {
        val local = if (question.allowMultiple) answers.multi[index]
        else listOfNotNull(answers.single[index])
        if (local.isNotEmpty()) return local
        // 提交文本每题占两行：题目行+回答行
        val answerLine = summaryLines.getOrNull(index * 2 + 1).orEmpty()
        if (answerLine.isBlank()) return emptyList()
        return question.options.filter { answerLine.contains(it) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = answered) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.clarify_card_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (answered) {
                            stringResource(R.string.clarify_answered_count, questions.size)
                        } else {
                            stringResource(R.string.clarify_questions_count, questions.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (answered) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.clarify_collapse else R.string.clarify_expand
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!answered) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    questions.forEachIndexed { index, question ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = questionTemplate.format(index + 1, question.question),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (question.allowMultiple) multiHint else singleHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            question.options.forEachIndexed { optionIndex, optionText ->
                                val checked = if (question.allowMultiple) {
                                    optionText in answers.multi[index]
                                } else {
                                    answers.single[index] == optionText
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (question.allowMultiple) {
                                                answers.multi[index] = if (checked) {
                                                    answers.multi[index] - optionText
                                                } else {
                                                    answers.multi[index] + optionText
                                                }
                                            } else {
                                                answers.single[index] = optionText
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (question.allowMultiple) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { newChecked ->
                                                answers.multi[index] = if (newChecked) {
                                                    answers.multi[index] + optionText
                                                } else {
                                                    answers.multi[index] - optionText
                                                }
                                            },
                                        )
                                    } else {
                                        RadioButton(
                                            selected = checked,
                                            onClick = { answers.single[index] = optionText },
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = optionLine.format(optionIndex + 1, optionText),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = answers.custom[index],
                                onValueChange = { answers.custom[index] = it },
                                label = { Text(customLabel) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = {
                        val summary = buildString {
                            questions.forEachIndexed { index, question ->
                                val picked = pickedFor(index, question)
                                val custom = answers.custom[index].trim()
                                append(questionTemplate.format(index + 1, question.question))
                                append('\n')
                                append(summaryPrefix)
                                append(if (picked.isNotEmpty()) picked.joinToString(listSeparator) else summaryNone)
                                if (custom.isNotBlank()) append(summaryCustom).append(custom)
                                if (index != questions.lastIndex) append('\n')
                            }
                        }
                        submittedSummary = summary
                        onApprove?.invoke(invocation, summary)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(stringResource(R.string.clarify_submit), fontWeight = FontWeight.Bold) }
            } else {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (expanded) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                    questions.forEachIndexed { index, question ->
                        Text(
                            text = questionTemplate.format(index + 1, question.question),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val picked = pickedFor(index, question)
                        question.options.forEach { optionText ->
                            val checked = optionText in picked
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (checked) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = optionText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val custom = answers.custom[index].trim()
                        if (custom.isNotBlank()) {
                            Text(
                                text = custom,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 每道问题的作答状态：多选集合/单选项/自定义输入 */
private class ClarifyAnswerState(count: Int) {
    val multi = mutableStateListOf<List<String>>()
    val single = mutableStateListOf<String?>()
    val custom = mutableStateListOf<String>()

    init {
        repeat(count) {
            multi.add(emptyList())
            single.add(null)
            custom.add("")
        }
    }
}

/** 是否为需要用户填表的交互式工具 */
fun isClarificationTool(name: String): Boolean = name == CLARIFICATION_TOOL

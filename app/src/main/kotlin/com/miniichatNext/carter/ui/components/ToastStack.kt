package com.miniichatNext.carter.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import com.miniichatNext.carter.util.AppThemeOverrides
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ToastAction
import com.miniichatNext.carter.data.model.ToastItem
import com.miniichatNext.carter.data.model.ToastTone
import kotlinx.coroutines.delay

private const val TOAST_SHOW_MS = 2000L
private const val TOAST_EXIT_MS = 250L
private const val MAX_STACK = 3

/**
 * 堆叠式toast栈：替换M3 SnackbarHost（单条队列做不到堆叠，且计时起点/关闭时机不可控）。
 * - 新条目在列表末尾=视觉底部，旧的往上推（Column+animateContentSize平滑移动）
 * - 每条条目独立LaunchedEffect计时：从自己显示后开始2s→shrink出场→从栈移除
 * （每条自己的协程，天然无跨条race）
 * - 动画分层：
 * ·堆叠移动/弹入弹出=AnimatedVisibility(expand/shrinkVertically+fade)，作用于条目占位
 * ·列表整体高度变化=animateContentSize
 * 关键：条目从数据移除（onRemove）与出场动画由同一条目状态机串行，不会互相重置
 */
@Composable
fun ToastStackHost(
    items: List<ToastItem>,
    onRemove: (String) -> Unit,
    onAction: (ToastItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 栈上限：最多同时3条，超出时立即移除最旧的（避免占满屏幕）
    LaunchedEffect(items.size) {
        if (items.size > MAX_STACK) {
            items.take(items.size - MAX_STACK).forEach { onRemove(it.id) }
        }
    }
    // 注意：不要加animateContentSize——条目自身的expand/shrink已平滑改变总高度，
    // 两层高度动画叠加会让下方条目"上移→回弹→再上移"（B移动两次的根因）
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            key(item.id) {
                ToastStackEntry(item = item, onRemove = onRemove, onAction = onAction)
            }
        }
    }
}

@Composable
private fun ToastStackEntry(
    item: ToastItem,
    onRemove: (String) -> Unit,
    onAction: (ToastItem) -> Unit,
) {
    var visible by remember(item.id) { mutableStateOf(false) }
    // dismissing=true：手动×触发的出场动画中（防重入）；动画播完再从栈移除
    var dismissing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, dismissing) {
        if (dismissing) {
            delay(TOAST_EXIT_MS)
            onRemove(item.id)
            return@LaunchedEffect
        }
        visible = true
        delay(TOAST_SHOW_MS)
        visible = false
        delay(TOAST_EXIT_MS)
        onRemove(item.id)
    }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(250)),
        exit = shrinkVertically(animationSpec = tween(TOAST_EXIT_MS.toInt())) +
            fadeOut(tween(TOAST_EXIT_MS.toInt())),
    ) {
        ToastCard(
            item = item,
            onAction = { onAction(item) },
            onDismiss = {
                if (!dismissing) {
                    dismissing = true
                    visible = false  // 触发出场动画
                }
            },
        )
    }
}

@Composable
private fun ToastCard(
    item: ToastItem,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val overrideVersion = AppThemeOverrides.version.intValue
    val overrides = remember(overrideVersion, dark) {
        AppThemeOverrides.load(context)[if (dark) "dark" else "light"].orEmpty()
    }
    val warningActionColor = AppThemeOverrides.customColor(
        overrides,
        "toastWarningAction",
        Color(0xFFB1A4FF),
    )
    val actionColor = if (item.tone == ToastTone.Normal) Color(0xFF4DD0E1) else warningActionColor
    Snackbar(
        // 固定深色配色（不随主题反转）：黑底白字，两种主题下观感一致
        containerColor = Color(0xE6222226),
        contentColor = Color(0xFFF2F1F7),
        // 青色调：与灰底对比更清晰（原浅紫与灰底不够突出）
        actionContentColor = actionColor,
        dismissActionContentColor = Color(0xFFF2F1F7),
        shape = RoundedCornerShape(12.dp),
        action = {
            when (item.action) {
                is ToastAction.Copy -> TextButton(onClick = onAction) {
                    Text(stringResource(R.string.copy))
                }
                is ToastAction.Manage -> TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                ) {
                    Text(stringResource(R.string.manage))
                }
                ToastAction.None -> {}
            }
        },
        dismissAction = if (item.withDismiss) {
            {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                    )
                }
            }
        } else null,
    ) {
        Text(item.message)
    }
}

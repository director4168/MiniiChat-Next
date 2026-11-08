package com.miniichatNext.carter.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.miniichatNext.carter.util.AppThemeOverrides
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 参考rikkahub的MessageJumper，消息滚动后短暂显示四向跳转。 */
@Composable
internal fun BoxScope.MessageJumper(state: LazyListState, messageCount: Int, onJump: () -> Unit) {
    val context = LocalContext.current
    val dark = LocalDarkMode.current
    val version = AppThemeOverrides.version.intValue
    val overrides = remember(context, dark, version) { AppThemeOverrides.load(context)[if (dark) "dark" else "light"].orEmpty() }
    val background = AppThemeOverrides.customColor(overrides, "messageJumperBackground", Color(0xCC82B1FF))
    val content = AppThemeOverrides.customColor(overrides, "messageJumperContent", Color(0xFF102A56))
    var visible by remember(state) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) visible = true
            else { delay(2500L); visible = false }
        }
    }
    AnimatedVisibility(
        visible = visible && messageCount > 1,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally { it * 2 }, exit = slideOutHorizontally { it * 2 },
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val icons = listOf(Icons.Default.KeyboardDoubleArrowUp, Icons.Default.KeyboardArrowUp,
                Icons.Default.KeyboardArrowDown, Icons.Default.KeyboardDoubleArrowDown)
            val labels = listOf(R.string.jump_first, R.string.jump_previous, R.string.jump_next, R.string.jump_last)
            icons.forEachIndexed { action, icon ->
                Surface(shape = CircleShape, tonalElevation = 0.dp,
                    color = background, contentColor = content) {
                    IconButton(modifier = Modifier.size(36.dp), onClick = {
                        onJump()
                        val target = when(action) {
                            0 -> 0
                            1 -> state.firstVisibleItemIndex - 1
                            2 -> state.firstVisibleItemIndex + 1
                            else -> messageCount - 1
                        }.coerceIn(0, (messageCount - 1).coerceAtLeast(0))
                        scope.launch {
                            when (action) {
                                0 -> state.scrollToItem(0)
                                3 -> state.scrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                else -> state.animateScrollToItem(target)
                            }
                        }
                    }) { Icon(icon, contentDescription = stringResource(labels[action]), modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

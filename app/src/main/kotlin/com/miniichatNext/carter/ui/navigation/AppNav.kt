package com.miniichatNext.carter.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 一次导航的目标。
 * [depth] 是它在返回栈里的层级，过渡动画靠比较前后两个NavKey的depth判断方向。
 */
data class NavKey(
    val route: String,
    val depth: Int = 0,
    val args: Map<String, String> = emptyMap(),
)

/**
 * 极简返回栈。
 * 只存路由（路由 -> 页面由AppRoot的when决定），避免引入导航库依赖。
 */
class NavController(initialRoute: String) {

    private val stack = mutableStateListOf(NavKey(initialRoute, 0))

    val entries: List<NavKey> get() = stack
    val current: NavKey get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(route: String, args: Map<String, String> = emptyMap()) {
        if (current.route == route) return
        stack.add(NavKey(route, stack.size, args))
    }

    fun replace(route: String, args: Map<String, String> = emptyMap()) {
        if (current.route == route) return
        stack[stack.lastIndex] = NavKey(route, stack.lastIndex, args)
    }

    /** 弹一层；栈底返回false */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** 回退到栈里已存在的 [route]；不在栈里返回false（不做任何事） */
    fun popUpTo(route: String): Boolean {
        val idx = stack.indexOfLast { it.route == route }
        if (idx < 0) return false
        while (stack.size - 1 > idx) stack.removeAt(stack.lastIndex)
        return true
    }

    /** 回退到 [route]；栈里没有就压一个（用于从聊天页直接进设置） */
    fun navigateUpTo(route: String): Boolean {
        if (popUpTo(route)) return true
        push(route)
        return false
    }

    /** 返回上一级：优先回到 [route]，否则弹一层 */
    fun back(route: String? = null): Boolean {
        if (route != null && popUpTo(route)) return true
        return pop()
    }

    fun resetTo(route: String) {
        stack.clear()
        stack.add(NavKey(route, 0))
    }

    companion object {
        /** 进程重建后恢复整个返回栈（之前只存了单个screen，栈信息会丢） */
        val Saver: androidx.compose.runtime.saveable.Saver<NavController, ArrayList<String>> =
            androidx.compose.runtime.saveable.Saver(
                save = { nav -> ArrayList(nav.entries.map { e -> e.route }) },
                restore = { routes ->
                    NavController(routes.firstOrNull() ?: "Chat").apply {
                        routes.drop(1).forEach { push(it) }
                    }
                }
            )
    }
}

/**
 * 统一的「返回上一级」调度器。
 *
 * 页面可注册**层级内**的返回处理器（例如MCP详情页要先回到列表页而不是退出该模块），
 * 后注册的优先处理；没有任何处理器消费时再由 [NavController] 弹栈；栈底交回系统。
 *
 * 后期扩展：调用 [registerHandler] 即可给任意路由挂自定义返回逻辑；
 * [fallback] 用于“再按一次退出”之类的全局兜底。
 */
class AppBackDispatcher {

    private class Entry(val route: String, val handler: () -> Boolean)

    private val entries = mutableStateMapOf<Long, Entry>()
    private var nextId = 0L

    var fallback: (() -> Boolean)? by mutableStateOf<(() -> Boolean)?>(null)

    /** 注册一个返回处理器；返回true表示已消费这次返回 */
    fun registerHandler(route: String, handler: () -> Boolean): Long {
        val id = nextId++
        entries[id] = Entry(route, handler)
        return id
    }

    fun unregisterHandler(token: Long) {
        entries.remove(token)
    }

    /** 当前路由是否存在可拦截的处理器（驱动BackHandler的enabled） */
    fun canIntercept(route: String): Boolean =
        fallback != null || entries.values.any { it.route == route }

    /** 依次询问处理器，全部返回false表示未消费 */
    fun dispatch(route: String): Boolean {
        val candidates = entries.entries.filter { it.value.route == route }.sortedByDescending { it.key }
        for (candidate in candidates) {
            if (candidate.value.handler()) return true
        }
        return fallback?.invoke() ?: false
    }
}

/**
 * 页面在自身层级内注册返回处理。
 * [enabled] 为false时不注册（例如模块已经处于根层级，返回应交给返回栈）。
 */
@Composable
fun NavBackInterceptor(
    dispatcher: AppBackDispatcher,
    route: String,
    enabled: Boolean = true,
    onBack: () -> Boolean,
) {
    val latest = rememberUpdatedState(onBack)
    DisposableEffect(dispatcher, route, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val token = dispatcher.registerHandler(route) { latest.value() }
        onDispose { dispatcher.unregisterHandler(token) }
    }
}

/** 页面切换动画；可按路由覆盖（见 [AppNavRegistry]） */
data class NavTransitions(
    val forward: ContentTransform,
    val backward: ContentTransform,
    val perRoute: Map<String, ContentTransform> = emptyMap(),
) {
    fun specFor(from: NavKey, to: NavKey): ContentTransform =
        perRoute[to.route]
            ?: perRoute[from.route]
            ?: if (to.depth > from.depth) forward else backward

    fun withRoute(route: String, transform: ContentTransform): NavTransitions =
        copy(perRoute = perRoute + (route to transform))
}

object NavTransitionPresets {

    private const val ENTER_MS = 260
    private const val EXIT_MS = 200

    /** 默认：横向轻微位移 + 淡入淡出（接近Material shared-axis） */
    val Slide: NavTransitions = NavTransitions(
        forward = slideInHorizontally(animationSpec = tween(ENTER_MS)) { it / 5 } + fadeIn(tween(ENTER_MS)) togetherWith
            slideOutHorizontally(animationSpec = tween(EXIT_MS)) { -it / 6 } + fadeOut(tween(EXIT_MS)),
        backward = slideInHorizontally(animationSpec = tween(ENTER_MS)) { -it / 6 } + fadeIn(tween(ENTER_MS)) togetherWith
            slideOutHorizontally(animationSpec = tween(EXIT_MS)) { it / 5 } + fadeOut(tween(EXIT_MS)),
    )

    /** 纯淡入淡出 */
    val Fade: NavTransitions = NavTransitions(
        forward = fadeIn(tween(ENTER_MS)) togetherWith fadeOut(tween(EXIT_MS)),
        backward = fadeIn(tween(ENTER_MS)) togetherWith fadeOut(tween(EXIT_MS)),
    )

    /** 缩放 + 淡入，适合弹层式的页面 */
    val Scale: NavTransitions = NavTransitions(
        forward = scaleIn(initialScale = 0.94f, animationSpec = tween(ENTER_MS)) + fadeIn(tween(ENTER_MS)) togetherWith
            scaleOut(targetScale = 1.04f, animationSpec = tween(EXIT_MS)) + fadeOut(tween(EXIT_MS)),
        backward = scaleIn(initialScale = 1.04f, animationSpec = tween(ENTER_MS)) + fadeIn(tween(ENTER_MS)) togetherWith
            scaleOut(targetScale = 0.94f, animationSpec = tween(EXIT_MS)) + fadeOut(tween(EXIT_MS)),
    )
}

/**
 * 全局导航注册表。
 * 后期要给某个页面换过渡动画或换整套动画，直接在这里注册即可，无需改AppRoot。
 */
object AppNavRegistry {

    var transitions: NavTransitions by mutableStateOf(NavTransitionPresets.Slide)

    private val routeTransitions = mutableStateMapOf<String, ContentTransform>()

    /** 给单个路由指定进入动画（返回时沿用同一套） */
    fun registerRouteTransition(route: String, transform: ContentTransform) {
        routeTransitions[route] = transform
    }

    fun unregisterRouteTransition(route: String) {
        routeTransitions.remove(route)
    }

    fun clearRouteTransitions() {
        routeTransitions.clear()
    }

    fun transformFor(from: NavKey, to: NavKey): ContentTransform =
        routeTransitions[to.route] ?: routeTransitions[from.route] ?: transitions.specFor(from, to)
}

/** 承载当前页面并播放进入/返回过渡动画 */
@Composable
fun AppNavHost(
    nav: NavController,
    modifier: Modifier = Modifier,
    content: @Composable (NavKey) -> Unit,
) {
    AnimatedContent(
        targetState = nav.current,
        modifier = modifier,
        transitionSpec = { AppNavRegistry.transformFor(initialState, targetState) },
        label = "app-nav",
    ) { key ->
        content(key)
    }
}

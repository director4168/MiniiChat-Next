package com.miniichatNext.carter.ui.markdown

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

@Composable
fun Markdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MarkdownDefaults.theme(style),
    components: MarkdownComponents = MarkdownDefaults.components,
    softBreakAsLineBreak: Boolean = true,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    Markdown(
        document = rememberMarkdownDocument(content, softBreakAsLineBreak),
        modifier = modifier,
        style = style,
        theme = theme,
        components = components,
        onLinkClick = onLinkClick,
    )
}

@Composable
fun Markdown(
    document: MarkdownDocument,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MarkdownDefaults.theme(style),
    components: MarkdownComponents = MarkdownDefaults.components,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val handlesLinks = onLinkClick != null
    val linkListener = remember(handlesLinks) {
        if (handlesLinks) {
            LinkInteractionListener { link ->
                if (link is LinkAnnotation.Url) currentOnLinkClick?.invoke(link.url)
            }
        } else null
    }
    val context = remember(theme, components, linkListener) {
        MarkdownContext(theme, components, linkListener)
    }
    CompositionLocalProvider(LocalMarkdownContext provides context) {
        ProvideTextStyle(style) {
            MarkdownBlocks(blocks = document.blocks, modifier = modifier)
        }
    }
}

private const val MARKDOWN_CACHE_MAX = 32
private const val MARKDOWN_CACHE_MAX_CHARS = 1_000_000
private data class MarkdownCacheKey(val source: String, val softBreakAsLineBreak: Boolean)

private object MarkdownDocumentCache {
    private val lock = Any()
    private val documents = LinkedHashMap<MarkdownCacheKey, MarkdownDocument>(64, 0.75f, true)
    private val pending = HashMap<MarkdownCacheKey, CompletableDeferred<MarkdownDocument?>>()

    fun get(source: String, softBreakAsLineBreak: Boolean): MarkdownDocument? = synchronized(lock) {
        documents[MarkdownCacheKey(source, softBreakAsLineBreak)]
    }

    fun put(source: String, softBreakAsLineBreak: Boolean, document: MarkdownDocument) {
        if (source.length > MARKDOWN_CACHE_MAX_CHARS) return
        synchronized(lock) {
            documents[MarkdownCacheKey(source, softBreakAsLineBreak)] = document
            while (documents.size > MARKDOWN_CACHE_MAX) {
                documents.entries.iterator().let { iterator -> iterator.next(); iterator.remove() }
            }
        }
    }

    fun begin(key: MarkdownCacheKey): Pair<CompletableDeferred<MarkdownDocument?>, Boolean> = synchronized(lock) {
        pending[key]?.let { return@synchronized it to false }
        CompletableDeferred<MarkdownDocument?>().also { pending[key] = it } to true
    }

    fun finish(key: MarkdownCacheKey, result: MarkdownDocument?, task: CompletableDeferred<MarkdownDocument?>) {
        synchronized(lock) {
            if (result != null) put(key.source, key.softBreakAsLineBreak, result)
            if (pending[key] === task) pending.remove(key)
            task.complete(result)
        }
    }
}

private val markdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val markdownSlots = Semaphore(2)

internal suspend fun preloadMarkdownDocument(source: String, softBreakAsLineBreak: Boolean = true) {
    parseMarkdownShared(source, softBreakAsLineBreak)
}

private suspend fun parseMarkdownShared(
    source: String,
    softBreakAsLineBreak: Boolean,
): MarkdownDocument? {
    MarkdownDocumentCache.get(source, softBreakAsLineBreak)?.let { return it }
    val key = MarkdownCacheKey(source, softBreakAsLineBreak)
    val (task, owner) = MarkdownDocumentCache.begin(key)
    if (owner) {
        markdownScope.launch {
            val parsed = runCatching {
                markdownSlots.withPermit {
                    withContext(Dispatchers.Default) { parseMarkdown(source, softBreakAsLineBreak) }
                }
            }.getOrNull()
            MarkdownDocumentCache.finish(key, parsed, task)
        }
    }
    return task.await()
}

@Composable
fun rememberMarkdownDocument(content: String, softBreakAsLineBreak: Boolean = true): MarkdownDocument {
    val state = remember(content, softBreakAsLineBreak) {
        mutableStateOf(
            MarkdownDocumentCache.get(content, softBreakAsLineBreak)
                ?: parseMarkdown(content, softBreakAsLineBreak).also {
                    MarkdownDocumentCache.put(content, softBreakAsLineBreak, it)
                }
        )
    }
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(state) {
        snapshotFlow { currentContent }
            .conflate()
            .filter { it != state.value.source }
            .mapNotNull { source -> parseMarkdownShared(source, softBreakAsLineBreak) }
            .flowOn(Dispatchers.Default)
            .collect { parsed -> state.value = parsed }
    }
    return state.value
}

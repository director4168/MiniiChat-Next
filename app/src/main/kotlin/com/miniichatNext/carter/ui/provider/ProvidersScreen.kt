/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.ui.components.OutlinedFieldBox
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.ui.markdown.MarkdownText

private const val MODELS_COLLAPSED_LIMIT = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvidersScreen(
    providers: List<ProviderConfig>,
    fetchingId: String?,
    activeProviderId: String,
    activeModel: String,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onFetchModels: (String) -> Unit,
    onAddManualModel: (String, String) -> Unit,
    onRemoveModel: (String, String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onReorder: (List<String>) -> Unit = {},
) {
    var orderedIds by remember { mutableStateOf(providers.map { it.id }) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val scroll = rememberLazyListState()
    val density = LocalDensity.current.density
    var draggedId by remember { mutableStateOf<String?>(null) }
    val currentProviders by rememberUpdatedState(providers)
    val currentReorder by rememberUpdatedState(onReorder)
    LaunchedEffect(providers.map { it.id }) {
        if (draggedId == null) orderedIds = providers.map { it.id }
    }
    var dragY by remember { mutableStateOf(0f) }
    val nameBounds = remember { mutableStateMapOf<String, Rect>() }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val edge = 56f * density
            val direction = when {
                dragY < listBounds.top + edge -> -1
                dragY > listBounds.bottom - edge -> 1
                else -> 0
            }
            if (direction != 0) {
                scroll.scroll { scrollBy(direction * 18f) }
                val dragging = draggedId
                val target = orderedIds.firstOrNull { id ->
                    id != dragging && bounds[id]?.let { dragY >= it.top && dragY <= it.bottom } == true
                }
                if (dragging != null && target != null) {
                    val next = orderedIds.toMutableList()
                    val from = next.indexOf(dragging)
                    val to = next.indexOf(target)
                    if (from >= 0 && to >= 0) { next.removeAt(from); next.add(to, dragging); orderedIds = next }
                }
            }
            kotlinx.coroutines.delay(16L)
        }
    }
    val ordered = orderedIds.mapNotNull { id -> providers.firstOrNull { it.id == id } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.providers), onBack)

        LazyColumn(
            state = scroll,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { listBounds = it.boundsInWindow() }
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item { Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onCreate() }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.add_provider),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            }
            if (providers.isEmpty()) {
                item { SectionCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Storage, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.no_providers),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.no_providers_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
            } else {
                items(ordered, key = { it.id }) { p ->
                        ProviderCard(
                            provider = p,
                            cardModifier = Modifier.animateItem().onGloballyPositioned { bounds[p.id] = it.boundsInWindow() }
                                .background(if (draggedId == p.id) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                            nameModifier = Modifier.onGloballyPositioned { nameBounds[p.id] = it.boundsInWindow() }.pointerInput(p.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val early = withTimeoutOrNull(250L) {
                                        var released = false
                                        while (!released) {
                                            val e = awaitPointerEvent()
                                            released = e.changes.none { it.pressed } || e.changes.any { it.isConsumed || (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
                                        }
                                        true
                                    }
                                    if (early != true) {
                                        draggedId = p.id
                                        var pointerY = (nameBounds[p.id]?.top ?: 0f) + down.position.y
                                        dragY = pointerY
                                        var dropped = false
                                        try {
                                            var pressed = true
                                            while (pressed) {
                                                val e = awaitPointerEvent()
                                                val change = e.changes.firstOrNull { it.id == down.id } ?: break
                                                pressed = change.pressed
                                                pointerY = (nameBounds[p.id]?.top ?: 0f) + change.position.y
                                                dragY = pointerY
                                                change.consume()
                                                if (!pressed) dropped = true
                                                val target = orderedIds.firstOrNull { id ->
                                                    id != p.id && bounds[id]?.let { pointerY >= it.top && pointerY <= it.bottom } == true
                                                }
                                                if (target != null) {
                                                    val next = orderedIds.toMutableList()
                                                    val from = next.indexOf(p.id)
                                                    val to = next.indexOf(target)
                                                    next.removeAt(from); next.add(to, p.id)
                                                    orderedIds = next
                                                }
                                            }
                                        } finally {
                                            draggedId = null
                                            if (dropped) currentReorder(orderedIds)
                                            else orderedIds = currentProviders.map { it.id }
                                        }
                                    }
                                }
                            },
                            fetching = fetchingId == p.id,
                            activeModel = if (activeProviderId == p.id) activeModel else "",
                            onEdit = { onEdit(p) },
                            onDelete = { onDelete(p.id) },
                            onFetch = { onFetchModels(p.id) },
                            onAddManual = { m -> onAddManualModel(p.id, m) },
                            onRemoveModel = { m -> onRemoveModel(p.id, m) },
                            onSelectModel = { m -> onSelectModel(p.id, m) }
                        )
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    cardModifier: Modifier = Modifier,
    nameModifier: Modifier = Modifier,
    fetching: Boolean,
    activeModel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFetch: () -> Unit,
    onAddManual: (String) -> Unit,
    onRemoveModel: (String) -> Unit,
    onSelectModel: (String) -> Unit
) {
    var manualInput by remember { mutableStateOf("") }
    var deleteOpen by remember { mutableStateOf(false) }
    var modelsExpanded by remember(provider.id) { mutableStateOf(false) }

    SectionCard(modifier = cardModifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable(onClick = onEdit)) {
                    Text(provider.name,
                        modifier = nameModifier,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(provider.baseUrl,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { deleteOpen = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !fetching, onClick = onFetch)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (fetching) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Default.Download, contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (fetching) stringResource(R.string.fetching)
                            else stringResource(R.string.fetch_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${provider.modelIds().size} models",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedFieldBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (manualInput.isEmpty()) {
                                Text(
                                    stringResource(R.string.add_model_manually),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    val canAdd = manualInput.trim().isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (canAdd) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                            )
                            .clickable(enabled = canAdd) {
                                onAddManual(manualInput.trim())
                                manualInput = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add, contentDescription = "add",
                            modifier = Modifier.size(16.dp),
                            tint = if (canAdd) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            if (provider.modelIds().isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val total = provider.modelIds().size
                val showAll = modelsExpanded || total <= MODELS_COLLAPSED_LIMIT
                val visible = if (showAll) provider.modelIds() else provider.modelIds().take(MODELS_COLLAPSED_LIMIT)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visible.forEach { m ->
                        ModelChip(
                            label = m,
                            selected = m == activeModel,
                            onSelect = { onSelectModel(m) },
                            onRemove = { onRemoveModel(m) }
                        )
                    }
                }
                if (total > MODELS_COLLAPSED_LIMIT) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { modelsExpanded = !modelsExpanded }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (modelsExpanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (modelsExpanded)
                                    stringResource(R.string.collapse_models)
                                else
                                    stringResource(R.string.expand_models, total - MODELS_COLLAPSED_LIMIT),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (deleteOpen) {
        com.miniichatNext.carter.ui.components.ConfirmDeleteDialog(
            targetName = provider.name,
            typeLabel = stringResource(R.string.item_provider),
            bodyText = stringResource(R.string.provider_delete_confirm, provider.name),
            onConfirm = { onDelete(); deleteOpen = false },
            onDismiss = { deleteOpen = false },
        )
    }
}

@Composable
private fun ModelChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Default.Close, contentDescription = "remove",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

package com.miniichatNext.carter.debug.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.ui.settings.LocalAppPreferences
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.miniichatNext.carter.util.AppThemeOverrides
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = LocalAppPreferences.current
    val dark = LocalDarkMode.current
    val overrideVersion = AppThemeOverrides.version.intValue
    val matchStyle = remember(context, dark, overrideVersion) {
        val mode = if (dark) "dark" else "light"
        val colors = AppThemeOverrides.currentSchemeHexes(dark) + AppThemeOverrides.load(context)[mode].orEmpty()
        SpanStyle(
            color = requireNotNull(AppThemeOverrides.parseHex(colors.getValue("logSearchHighlight"))),
            background = requireNotNull(AppThemeOverrides.parseHex(colors.getValue("logSearchBackground"))),
        )
    }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selectedPath by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var search by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(-1) }
    var refreshVersion by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val matches = remember(lines, search) {
        if (search.isBlank()) emptyList() else lines.indices.filter { lines[it].contains(search, ignoreCase = true) }
    }
    val selected = files.firstOrNull { it.absolutePath == selectedPath }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && selected != null) scope.launch {
            try {
                val source = selected
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    } ?: error("Destination is not writable")
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; status = error.message.orEmpty() }
        }
    }
    LaunchedEffect(refreshVersion, selectedPath) {
        try {
            val result = withContext(Dispatchers.IO) {
                val all = DebugLog.dir(context).walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("log", "txt") }
                    .sortedBy { it.relativeTo(DebugLog.dir(context)).path }.toList()
                val file = all.firstOrNull { it.absolutePath == selectedPath } ?: all.firstOrNull()
                val text = file?.let { DebugLog.readLogFile(context, it) }.orEmpty()
                all to (file?.absolutePath.orEmpty() to text.lines())
            }
            files = result.first
            selectedPath = result.second.first
            lines = result.second.second
            matchIndex = -1
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            status = error.message.orEmpty()
            lines = emptyList()
        }
    }
    fun findNext(step: Int) {
        if (matches.isEmpty()) return
        matchIndex = if (matchIndex < 0) { if (step < 0) matches.lastIndex else 0 } else (matchIndex + step + matches.size) % matches.size
        scope.launch { listState.scrollToItem(matches[matchIndex]) }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        SettingsTopBar(stringResource(R.string.log_viewer_title), onBack)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            files.forEach { file ->
                TextButton(onClick = { selectedPath = file.absolutePath }) {
                    Text(file.relativeTo(DebugLog.dir(context)).path,
                        color = if (file == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it; matchIndex = -1 },
            label = { Text(stringResource(R.string.log_search)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { findNext(-1) }, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.log_previous)) }
            TextButton(onClick = { findNext(1) }, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.log_next)) }
            Text("${(matchIndex + 1).coerceAtLeast(0)}/${matches.size}", modifier = Modifier.padding(top = 14.dp))
            TextButton(onClick = { refreshVersion++ }) { Text(stringResource(R.string.log_refresh)) }
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log", lines.joinToString("\n")))
            }) { Text(stringResource(R.string.copy)) }
            TextButton(enabled = selected != null, onClick = {
                scope.launch {
                    try {
                        val file = selected ?: return@launch
                        val shared = File(context.cacheDir, "shared/log_${System.currentTimeMillis()}.log")
                        val snapshot = lines.joinToString("\n")
                        withContext(Dispatchers.IO) {
                            shared.parentFile?.mkdirs()
                            shared.writeText(snapshot)
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, file.name))
                    } catch (error: Exception) { if (error is CancellationException) throw error; status = error.message.orEmpty() }
                }
            }) { Text(stringResource(R.string.log_share)) }
            TextButton(enabled = selected != null, onClick = { selected?.let { exportLauncher.launch(it.name) } }) { Text(stringResource(R.string.log_export)) }
            TextButton(enabled = selected != null, onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        if (files.isEmpty()) Text(stringResource(R.string.log_empty), modifier = Modifier.padding(16.dp))
        val lineModifier = Modifier.fillMaxWidth()
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(lines) { index, line ->
                Row(modifier = lineModifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text((index + 1).toString(), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width((lines.size.toString().length * 8 + 16).dp))
                    SelectionContainer(modifier = if (preferences.codeWrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(horizontal)) {
                        val ranges = remember(line, search) { findLogSearchRanges(line, search) }
                        val highlighted = remember(line, ranges, matchStyle) {
                            buildAnnotatedString {
                                append(line.ifEmpty { " " })
                                ranges.forEach { range ->
                                    addStyle(matchStyle, range.first, range.last + 1)
                                }
                            }
                        }
                        Text(highlighted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            softWrap = preferences.codeWrap,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.log_delete_title)) },
        text = { Text(selected?.name.orEmpty()) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            val file = selected ?: return@TextButton
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { DebugLog.deleteLogFile(context, file) }
                    lines = emptyList(); refreshVersion++
                } catch (error: Exception) { if (error is CancellationException) throw error; status = error.message.orEmpty() }
            }
        }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

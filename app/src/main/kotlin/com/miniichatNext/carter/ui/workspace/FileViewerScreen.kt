package com.miniichatNext.carter.ui.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.workspace.WorkspaceRepository
import com.miniichatNext.carter.data.workspace.WorkspaceStorageArea
import com.miniichatNext.carter.ui.components.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区文本文件查看/编辑器.
 *
 * FILES区可编辑并保存；LINUX区只读预览，避免误改Rootfs系统文件。
 * 大于[WorkspaceRepository.maxReadBytes]的文件拒绝加载/编辑。
 */
@Composable
fun FileViewerScreen(
    repository: WorkspaceRepository,
    workspaceId: String,
    area: WorkspaceStorageArea,
    path: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val maxBytes = repository.config.maxReadBytes

    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var tooLarge by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workspaceId, area, path) {
        loading = true
        tooLarge = false
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                repository.fileSize(workspaceId, area, path)
            }
        }.onSuccess { size ->
            if (size > maxBytes) {
                tooLarge = true
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.readTextForPreview(workspaceId, area, path)
                    }
                }.onSuccess { content ->
                    text = content
                    dirty = false
                }.onFailure { t ->
                    loadError = t.message ?: t::class.simpleName
                }
            }
        }.onFailure { t ->
            loadError = t.message ?: t::class.simpleName
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(
            title = fileName,
            onBack = {
                if (dirty && editable) {
                    Toast.makeText(context, R.string.workspace_file_save_failed, Toast.LENGTH_SHORT).show()
                }
                onBack()
            },
            actions = {
                if (editable && !tooLarge) {
                    IconButton(
                        enabled = !saving && dirty,
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = runCatching {
                                    withContext(Dispatchers.IO) {
                                        repository.writeText(workspaceId, area, path, text)
                                    }
                                }.isSuccess
                                saving = false
                                if (ok) {
                                    dirty = false
                                    Toast.makeText(context, R.string.workspace_file_save_done, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.workspace_file_save_failed, ""), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save))
                    }
                }
            }
        )

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadError != null -> Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.workspace_file_load_failed, loadError.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            tooLarge -> Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.workspace_file_too_large, "%.1f KB".format(maxBytes / 1024.0)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(modifier = Modifier.fillMaxSize()) {
                if (!editable) {
                    Text(
                        stringResource(R.string.workspace_file_read_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                val scroll = rememberScrollState()
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        dirty = true
                    },
                    enabled = editable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (editable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    ),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
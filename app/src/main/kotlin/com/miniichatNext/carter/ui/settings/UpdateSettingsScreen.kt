package com.miniichatNext.carter.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新设置页（路径：设置→关于→更新设置）
 * 功能：
 * - 启动时自动检查更新（Switch，持久化）
 * - 立即检查新版本（按钮）
 * - 已忽略的版本列表（可清空）
 */
@Composable
fun UpdateSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }

    // 订阅settings实时变化（autoCheckUpdate+已忽略版本）
    var autoCheck by remember { mutableStateOf(true) }
    var ignoredVersions by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        val s = settingsRepo.settings.first()
        autoCheck = s.autoCheckUpdate
        ignoredVersions = s.ignoredUpdateVersions
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                stringResource(R.string.update_settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // 启动时自动检查更新（Switch）
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newVal = !autoCheck
                            autoCheck = newVal
                            scope.launch { settingsRepo.setAutoCheckUpdate(newVal) }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.update_auto_check_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.update_auto_check_sub),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = { newVal ->
                            autoCheck = newVal
                            scope.launch { settingsRepo.setAutoCheckUpdate(newVal) }
                        },
                    )
                }
            }

            // 立即检查新版本（按钮）
            Spacer(Modifier.height(8.dp))
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    com.miniichatNext.carter.data.update.UpdateRepository.checkForUpdate(context)
                                }
                                when (result) {
                                    is com.miniichatNext.carter.data.update.UpdateRepository.Result.Success -> {
                                        val info = result.info
                                        val ver = runCatching {
                                            context.packageManager
                                                .getPackageInfo(context.packageName, 0).versionName
                                        }.getOrNull() ?: ""
                                        if (info.version.isBlank() ||
                                            com.miniichatNext.carter.data.update.compareVersions(info.version, ver) <= 0
                                        ) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.update_no_update),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            // 手动检查：直接设pending（不过滤ignored——用户主动点就是要看）
                                            com.miniichatNext.carter.data.update.UpdateState.forceShowUpdate(info)
                                        }
                                    }
                                    is com.miniichatNext.carter.data.update.UpdateRepository.Result.Failure -> {
                                        // 手动检查失败→明确提示（含超时）
                                        val msg = if (result.message == "timeout")
                                            context.getString(R.string.update_check_timeout)
                                        else
                                            context.getString(R.string.update_check_failed, result.message)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.update_check),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.update_check_sub),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 已忽略的版本：不需要展示列表（忽略行为是"自动检查时不弹该版本"，下次有新版本自动失效）

            Spacer(Modifier.height(24.dp))
        }
    }
}
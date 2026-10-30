package com.miniichatNext.carter.debug.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.miniichatNext.carter.R
import java.io.File
import com.miniichatNext.carter.debug.DebugLevel
import com.miniichatNext.carter.debug.DebugLog

private const val FEEDBACK_EMAIL = "director4168@163.com"

/** 反馈邮件（带日志zip附件）：chooser也要带读权限标志，否则邮件应用拿不到附件 */
internal fun sendFeedbackMail(context: Context, zip: File, subject: String, body: String) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    }.getOrNull()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.chooser_send_mail)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}

/**
 * 崩溃反馈：只走mailto（与设置-关于页的“联系开发者”一致），
 * 选择器里只出现邮箱应用；崩溃日志直接内联在正文里，不依赖附件。
 */
internal fun sendCrashFeedbackMail(
    context: Context,
    subject: String,
    body: String,
    crashLog: String,
) {
    val text = if (crashLog.isBlank()) body else body + "\n\n" + crashLog.take(MAX_INLINE_CRASH_LOG)
    runCatching {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.about_mail_chooser))
        )
    }
}

/** 正文内联的崩溃日志长度上限 */
private const val MAX_INLINE_CRASH_LOG = 8000

internal fun shareZip(context: Context, zip: File, title: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}

@Composable
fun DebugScreen(
    onBack: () -> Unit,
    vm: com.miniichatNext.carter.vm.ChatViewModel? = null,
    onOpenDialogTest: () -> Unit = {},
    onOpenThemeColors: () -> Unit = {},
    onOpenCropTest: () -> Unit = {},
) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(DebugLog.level) }
    // 崩溃测试确认弹窗（null=不弹；"main"/"bg"=确认后执行对应崩溃）
    var crashConfirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(level) { DebugLog.setLevel(context, level) }

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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                stringResource(R.string.debug_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.debug_level_label, level.name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DebugLevel.entries.forEach { lvl ->
                    FilterChip(
                        selected = level == lvl,
                        onClick = { level = lvl },
                        label = { Text(lvl.label, fontSize = 12.sp) }
                    )
                }
            }

            // 调试悬浮球开关：开启后所有页面右下角出现"LOG"球，点击快速切换日志等级
            val appSettings by (vm?.settings ?: kotlinx.coroutines.flow.flowOf<com.miniichatNext.carter.data.store.AppSettings?>(null))
                .collectAsState(initial = null)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.debug_log_ball),
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                )
                androidx.compose.material3.Switch(
                    checked = appSettings?.debugLogBall == true,
                    onCheckedChange = { on ->
                        vm?.updateSettings { it.copy(debugLogBall = on) }
                        if (on) {
                            if (com.miniichatNext.carter.debug.DebugLogBallService.canDraw(context)) {
                                com.miniichatNext.carter.debug.DebugLogBallService.start(context)
                            } else {
                                // 引导去系统设置授予"显示在其他应用上层"权限
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:" + context.packageName),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.debug_log_ball_permission),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } else {
                            com.miniichatNext.carter.debug.DebugLogBallService.stop(context)
                        }
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    DebugLog.clear(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.debug_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text(stringResource(R.string.debug_clear)) }
                TextButton(onClick = {
                    runCatching {
                        shareZip(
                            context,
                            DebugLog.exportZip(context),
                            context.getString(R.string.debug_export_title)
                        )
                    }
                }) { Text(stringResource(R.string.debug_export_share)) }
                TextButton(onClick = {
                    runCatching {
                        sendFeedbackMail(
                            context,
                            DebugLog.exportZip(context),
                            context.getString(R.string.debug_mail_subject),
                            context.getString(R.string.debug_mail_body)
                        )
                    }
                }) { Text(stringResource(R.string.debug_mail_feedback)) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            Text(stringResource(R.string.debug_crash_test), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { crashConfirm = "main" }) {
                    Text(
                        stringResource(R.string.debug_crash_main),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = { crashConfirm = "bg" }) {
                    Text(
                        stringResource(R.string.debug_crash_bg),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 弹窗类型测试入口（独立页）——卡片式（与设置页导航入口一致）
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            com.miniichatNext.carter.ui.components.SectionCard {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDialogTest)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            stringResource(R.string.debug_dialog_test_screen),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        androidx.compose.material3.Text(
                            stringResource(R.string.debug_dialog_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            com.miniichatNext.carter.ui.components.SectionCard {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenThemeColors)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            stringResource(R.string.debug_theme_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        androidx.compose.material3.Text(
                            stringResource(R.string.debug_theme_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            com.miniichatNext.carter.ui.components.SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenCropTest)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.debug_crop_test_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.debug_crop_test_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }

    // 崩溃测试确认弹窗：警告→确定后才真正崩
    crashConfirm?.let { kind ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { crashConfirm = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.debug_crash_confirm_title)) },
            text = { com.miniichatNext.carter.ui.markdown.MarkdownText(
                text = androidx.compose.ui.res.stringResource(R.string.debug_crash_confirm_message)
            ) },
            confirmButton = {
                TextButton(onClick = {
                    crashConfirm = null
                    if (kind == "main") {
                        DebugLog.i("CrashTest", "trigger java crash on main thread")
                        throw RuntimeException("MiniiChat Next Debug crash test (main thread)")
                    } else {
                        DebugLog.i("CrashTest", "trigger java crash on background thread")
                        Thread {
                            Thread.sleep(200)
                            throw RuntimeException("MiniiChat Next Debug crash test (background thread)")
                        }.start()
                    }
                }) { Text(stringResource(R.string.debug_crash_confirm_yes), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { crashConfirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

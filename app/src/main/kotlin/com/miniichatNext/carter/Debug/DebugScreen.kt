package com.miniichatNext.carter.Debug

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

private const val FEEDBACK_EMAIL = "director4168@163.com"

internal fun sendFeedbackMail(context: Context, zip: File, subject: String, body: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.chooser_send_mail))
        )
    }
}

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
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(DebugLog.level) }

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

            // 操作行
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

            // Java层崩溃测试
            Text(stringResource(R.string.debug_crash_test), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    DebugLog.i("CrashTest", "trigger java crash on main thread")
                    throw RuntimeException("MiniiChat Next Debug crash test (main thread)")
                }) {
                    Text(
                        stringResource(R.string.debug_crash_main),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = {
                    DebugLog.i("CrashTest", "trigger java crash on background thread")
                    Thread {
                        Thread.sleep(200)
                        throw RuntimeException("MiniiChat Next Debug crash test (background thread)")
                    }.start()
                }) {
                    Text(
                        stringResource(R.string.debug_crash_bg),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            Text(
                stringResource(R.string.debug_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

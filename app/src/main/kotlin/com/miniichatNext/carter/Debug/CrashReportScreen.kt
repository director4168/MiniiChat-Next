package com.miniichatNext.carter.Debug

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R

@Composable
fun CrashReportScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val crashText = remember { CrashLogger.readLatestCrash(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.crash_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            stringResource(R.string.crash_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                runCatching {
                    shareZip(
                        context,
                        CrashLogger.crashZipFile(context),
                        context.getString(R.string.crash_export_title)
                    )
                }
            }) { Text(stringResource(R.string.debug_export_share)) }
            TextButton(onClick = {
                runCatching {
                    sendFeedbackMail(
                        context,
                        CrashLogger.crashZipFile(context),
                        context.getString(R.string.crash_mail_subject),
                        context.getString(R.string.crash_mail_body)
                    )
                }
            }) { Text(stringResource(R.string.debug_mail_feedback)) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_ignore)) }
        }

        Text(
            text = crashText.ifBlank { stringResource(R.string.crash_empty) },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        )
        Spacer(Modifier.height(12.dp))
        // 仅组合时记一条（remember防止重组重复写入）
        remember { DebugLog.i("CrashPage", "crash report shown (${crashText.length} chars)") }
    }
}

package com.miniichatNext.carter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.miniichatNext.carter.BuildConfig
import com.miniichatNext.carter.R
import com.miniichatNext.carter.Debug.DebugLog
import com.miniichatNext.carter.Debug.shortVibrate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenDebug: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 运行时取版本号
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("?")
    // versionCode一并展示
    val versionCode = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrDefault(0L)

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
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(
                stringResource(R.string.section_about),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SectionHeader(stringResource(R.string.section_about))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.about_text_intro))
                            append("\n")
                            withLink(
                                LinkAnnotation.Url(
                                    "https://github.com/director4168/MiniiChat-Next",
                                    styles = TextLinkStyles(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    )
                                )
                            ) { append("MiniiChat Next") }
                            append("\n")
                            withLink(
                                LinkAnnotation.Url(
                                    "https://github.com/Minis233/miniichat",
                                    styles = TextLinkStyles(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    )
                                )
                            ) { append("MiniiChat") }
                            append("\n")
                            withLink(
                                LinkAnnotation.Url(
                                    "https://github.com/rikkahub/rikkahub",
                                    styles = TextLinkStyles(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    )
                                )
                            ) { append("RikkaHub") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 长按版本卡片进入Debug
            Spacer(Modifier.height(8.dp))
            SectionCard {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { _ ->
                                    val job = scope.launch {
                                        delay(350)
                                        shortVibrate(context)
                                        onOpenDebug()
                                    }
                                    tryAwaitRelease()
                                    job.cancel()
                                }
                            )
                        }
                ) {
                    Text(
                        stringResource(R.string.about_version_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$versionName ($versionCode)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 构建信息（构建方式、构建日期）
            Spacer(Modifier.height(8.dp))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        stringResource(R.string.about_build_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    val buildDateText = remember {
                        runCatching {
                            java.text.DateFormat
                                .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                .format(java.util.Date(BuildConfig.BUILD_TIMESTAMP))
                        }.getOrDefault("?")
                    }
                    Text(
                        "${BuildConfig.BUILD_TYPE} · $buildDateText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ---- 反馈与支持：GitHub Issue / 联系开发者 / 日志导出 ----
            Spacer(Modifier.height(8.dp))
            SectionCard {
                Column {
                    AboutActionRow(
                        icon = Icons.Default.BugReport,
                        title = stringResource(R.string.about_issue_title),
                        subtitle = stringResource(R.string.about_issue_sub),
                        onClick = {
                            val url = "https://github.com/director4168/MiniiChat-Next/issues"
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure { e ->
                                DebugLog.w("About", "cannot open issue url: ${e.message}")
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.about_open_link_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                    AboutActionRow(
                        icon = Icons.Default.Email,
                        title = stringResource(R.string.about_contact_title),
                        subtitle = stringResource(R.string.about_contact_sub),
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:" + MAIL_ADDRESS)
                                ).apply {
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        context.getString(R.string.about_mail_subject)
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        context.getString(R.string.about_mail_chooser)
                                    )
                                )
                            }.onFailure { e ->
                                DebugLog.w("About", "cannot open mail app: ${e.message}")
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.about_open_link_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                    AboutActionRow(
                        icon = Icons.Default.FileUpload,
                        title = stringResource(R.string.about_export_logs_title),
                        subtitle = stringResource(R.string.about_export_logs_sub),
                        onClick = {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    DebugLog.exportZipToDownloads(context)
                                }
                                DebugLog.i("About", "export logs -> ${saved ?: "failed"}")
                                Toast.makeText(
                                    context,
                                    if (saved != null) {
                                        context.getString(R.string.about_export_logs_done, saved)
                                    } else {
                                        context.getString(R.string.about_export_logs_failed)
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** GitHub 反馈邮箱（与项目 GitHub 账号同名） */
private const val MAIL_ADDRESS = "director4168@163.com"

@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


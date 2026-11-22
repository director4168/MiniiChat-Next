/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.tools.ShellAccess
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SectionHeader
import com.miniichatNext.carter.ui.components.SettingsTopBar
import com.miniichatNext.carter.util.hasStorageAccess
import com.miniichatNext.carter.util.openAppDetailsSettings
import com.miniichatNext.carter.util.openNotificationSettings
import com.miniichatNext.carter.util.openAllFilesAccessSettings
import com.miniichatNext.carter.util.storagePermissionNames
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AdvancedPermissionState(
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val rootAvailable: Boolean = false,
)

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var highRiskOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val shizukuListener = remember {
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh++ }
    }
    DisposableEffect(Unit) {
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) } }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val mediaPermissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
    ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val advanced by produceState(AdvancedPermissionState(), refresh) {
        value = withContext(Dispatchers.IO) {
            AdvancedPermissionState(
                shizukuAvailable = ShellAccess.isShizukuAvailable(),
                shizukuGranted = ShellAccess.isShizukuGranted(),
                rootAvailable = ShellAccess.isRootAvailable(),
            )
        }
    }
    val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // Android 8-12没有运行时通知权限，但仍可在系统设置里关闭通知，不能直接当作已授予。
    val notificationGranted = notificationPermissionGranted &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    val calendarGranted =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val storageGranted = hasStorageAccess(context)
    val mediaGranted = mediaPermissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    val installGranted = context.packageManager.canRequestPackageInstalls()
    val overlayGranted = Settings.canDrawOverlays(context)

    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) openAllFilesAccessSettings(context)
        else storageLauncher.launch(storagePermissionNames())
    }

    fun requestInstall() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        if (!runCatching { context.startActivity(intent) }.isSuccess) {
            openAppDetailsSettings(context)
        }
    }

    fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        if (!runCatching { context.startActivity(intent) }.isSuccess) {
            openAppDetailsSettings(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.permissions_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.permissions_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.permissions_refresh))
                }
            }

            SectionHeader(stringResource(R.string.permissions_app_section))
            SectionCard {
                Column {
                    PermissionRow(
                        title = stringResource(R.string.permissions_notification),
                        description = stringResource(R.string.permissions_notification_desc),
                        granted = notificationGranted,
                        action = when {
                            !notificationPermissionGranted -> {
                                { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            }
                            !notificationGranted -> {
                                { openNotificationSettings(context) }
                            }
                            else -> null
                        },
                        actionLabel = if (notificationPermissionGranted && !notificationGranted) {
                            stringResource(R.string.permissions_open_settings)
                        } else null,
                    )
                    PermissionDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_basic),
                        description = stringResource(R.string.permissions_basic_desc),
                        granted = true,
                    )
                    PermissionDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_media),
                        description = stringResource(R.string.permissions_media_desc),
                        granted = mediaGranted,
                        action = { mediaLauncher.launch(mediaPermissions) },
                    )
                    PermissionDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_calendar),
                        description = stringResource(R.string.permissions_calendar_desc),
                        granted = calendarGranted,
                        action = {
                            calendarLauncher.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            )
                        },
                    )
                    PermissionDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_storage),
                        description = stringResource(R.string.permissions_storage_desc),
                        granted = storageGranted,
                        action = ::requestStorage,
                    )
                    PermissionDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_install),
                        description = stringResource(R.string.permissions_install_desc),
                        granted = installGranted,
                        action = ::requestInstall,
                    )
                    PermissionDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_overlay),
                        description = stringResource(R.string.permissions_overlay_desc),
                        granted = overlayGranted,
                        action = ::requestOverlay,
                    )
                }
            }

            SectionHeader(stringResource(R.string.permissions_advanced_section))
            SectionCard {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { highRiskOpen = !highRiskOpen }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.permissions_high_risk), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.permissions_high_risk_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (highRiskOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    if (highRiskOpen) {
                        PermissionDivider()
                        PermissionRow(
                            title = "Shizuku",
                            description = stringResource(R.string.permissions_shizuku_desc),
                            granted = advanced.shizukuGranted,
                            statusText = when {
                                !advanced.shizukuAvailable -> stringResource(R.string.permissions_unavailable)
                                advanced.shizukuGranted -> stringResource(R.string.permissions_granted)
                                else -> stringResource(R.string.permissions_not_granted)
                            },
                            action = {
                                if (ShellAccess.isShizukuAvailable()) ShellAccess.requestShizukuPermission()
                                refresh++
                            },
                        )
                        PermissionDivider()
                        PermissionRow(
                            title = "Root",
                            description = stringResource(R.string.permissions_root_desc),
                            granted = advanced.rootAvailable,
                            statusText = if (advanced.rootAvailable) stringResource(R.string.permissions_available)
                            else stringResource(R.string.permissions_unavailable),
                            action = { refresh++ },
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    action: (() -> Unit)? = null,
    actionLabel: String? = null,
    statusText: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                statusText ?: if (granted) stringResource(R.string.permissions_granted)
                else stringResource(R.string.permissions_not_granted),
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (action != null) {
            TextButton(onClick = action) {
                Text(
                    actionLabel ?: if (granted) stringResource(R.string.permissions_refresh)
                    else stringResource(R.string.permissions_request)
                )
            }
        }
    }
}

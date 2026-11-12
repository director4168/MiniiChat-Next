/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.backup.BackupManager
import com.miniichatNext.carter.data.backup.BackupManifest
import com.miniichatNext.carter.data.backup.BackupMode
import com.miniichatNext.carter.data.backup.RestoreMode
import com.miniichatNext.carter.vm.ChatViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { BackupManager(context.applicationContext, vm.store) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stagedExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importManifest by remember { mutableStateOf<BackupManifest?>(null) }
    var exportedMissing by rememberSaveable { mutableStateOf(0) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var exportPasswordOpen by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    BackHandler(enabled = working) { }
    val completed = stringResource(R.string.backup_done)
    val saved = stringResource(R.string.backup_saved)
    val missingHint = stringResource(R.string.backup_missing)
    val passwordError = stringResource(R.string.backup_password_error)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = stagedExportPath?.let(::File)
        if (uri != null && file != null) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Destination is not writable")
                }
                status = saved + if (exportedMissing > 0) "\n$missingHint: $exportedMissing" else ""
            } catch (error: Exception) { if (error is CancellationException) throw error; status = error.message.orEmpty() }
            finally { working = false; file.delete(); stagedExportPath = null }
        } else { file?.delete(); stagedExportPath = null }
    }
    fun exportBackup(mode: BackupMode, secret: CharArray = charArrayOf()) {
        val session = vm.taskSession
        vm.stopTaskSession()
        working = true
        scope.launch {
            try {
                session?.awaitStopped()
                val (file, manifest) = manager.export(mode, secret)
                stagedExportPath = file.absolutePath
                exportedMissing = manifest.missingFileCount
                exportLauncher.launch(file.name)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                status = error.message.orEmpty()
            } finally { working = false; secret.fill('\u0000') }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            password = ""; importUri = null; importManifest = null; replaceConfirm = false
            working = true
            scope.launch {
                try {
                    importManifest = manager.inspect(uri)
                    importUri = uri
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    status = error.message.orEmpty()
                } finally { working = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        com.miniichatNext.carter.ui.components.SettingsTopBar(stringResource(R.string.backup_title), onBack = { if (!working) onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.backup_warning), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.backup_scope), style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !working, onClick = { exportBackup(BackupMode.QUICK) }) {
                Text(stringResource(R.string.backup_quick))
            }
            Text(stringResource(R.string.backup_quick_hint), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !working, onClick = {
                password = ""; confirmPassword = ""; exportPasswordOpen = true
            }) { Text(stringResource(R.string.backup_encrypted)) }
            Text(stringResource(R.string.backup_encrypted_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !working, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                Text(stringResource(R.string.backup_import))
            }
            if (working) CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (exportPasswordOpen) AlertDialog(
        onDismissRequest = { exportPasswordOpen = false; password = ""; confirmPassword = "" },
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text(stringResource(R.string.backup_password_confirm)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = password.isNotEmpty() && password == confirmPassword, onClick = {
            val secret = password.toCharArray()
            password = ""; confirmPassword = ""; exportPasswordOpen = false
            exportBackup(BackupMode.ENCRYPTED, secret)
        }) { Text(stringResource(R.string.backup_export)) } },
        dismissButton = { TextButton(onClick = { exportPasswordOpen = false; password = ""; confirmPassword = "" }) { Text(stringResource(R.string.cancel)) } },
    )
    if (importUri != null) {
        val uri = importUri!!
        val needsPassword = importManifest?.encrypted == true
        fun restore(mode: RestoreMode) {
            if (needsPassword && password.isEmpty()) return
            val secret = password.toCharArray()
            password = ""
            importUri = null
            importManifest = null
            val session = vm.taskSession
            vm.stopTaskSession()
            working = true
            scope.launch {
                try {
                    session?.awaitStopped()
                    manager.restore(uri, mode, secret)
                    status = completed
                    com.miniichatNext.carter.util.AppThemeOverrides.version.intValue++
                    context.getSharedPreferences("locale_cache", android.content.Context.MODE_PRIVATE)
                        .edit().putString("language", vm.settingsRepo.settings.first().language).commit()
                    val intent = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                } catch (error: Exception) { if (error is CancellationException) throw error; status = if (error is javax.crypto.BadPaddingException) passwordError else error.message.orEmpty() }
                finally { working = false; secret.fill('\u0000') }
            }
        }
        if (replaceConfirm) AlertDialog(
            onDismissRequest = { replaceConfirm = false },
            title = { Text(stringResource(R.string.backup_replace)) },
            text = { Text(stringResource(R.string.backup_replace_warning)) },
            confirmButton = { TextButton(onClick = { replaceConfirm = false; restore(RestoreMode.REPLACE) }) { Text(stringResource(R.string.backup_replace)) } },
            dismissButton = { TextButton(onClick = { replaceConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
        else AlertDialog(
            onDismissRequest = { importUri = null; password = "" },
            title = { Text(stringResource(R.string.backup_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_restore_confirm))
                    Text(stringResource(if (needsPassword) R.string.backup_detected_encrypted else R.string.backup_detected_quick))
                    if (needsPassword) OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.backup_password)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !needsPassword || password.isNotEmpty(), onClick = { restore(RestoreMode.MERGE) }) { Text(stringResource(R.string.backup_merge)) }
                        TextButton(enabled = !needsPassword || password.isNotEmpty(), onClick = { replaceConfirm = true }) { Text(stringResource(R.string.backup_replace)) }
                    }
                    TextButton(onClick = { importUri = null; password = "" }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

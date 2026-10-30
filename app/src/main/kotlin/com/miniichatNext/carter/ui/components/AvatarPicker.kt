package com.miniichatNext.carter.ui.components

import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.avatar.AvatarStorage
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.miniichatNext.carter.debug.DebugLog


@Composable
fun AvatarPicker(
    current: Avatar,
    onChange: (Avatar) -> Unit,
    onDismiss: () -> Unit,
    canonicalPath: String? = null,
) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val newId = remember { com.miniichatNext.carter.util.newId() }

    var emojiDraft by remember { mutableStateOf((current as? Avatar.Emoji)?.content ?: "") }

    var lastPickedUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val importOriginalImage: () -> Unit = {
        val uri = lastPickedUri
        if (uri == null) {
            com.miniichatNext.carter.debug.DebugLog.w(
                "AvatarPicker", "crop unavailable and no source uri to fall back to"
            )
            Toast.makeText(
                context,
                context.getString(R.string.avatar_crop_unusable),
                Toast.LENGTH_LONG
            ).show()
        } else {
            scope.launch {
                val path = if (canonicalPath != null) {
                    AvatarStorage.saveFromUriToPath(context, uri, canonicalPath, maxSide = 1024)
                } else {
                    AvatarStorage.saveFromUri(context, newId(), uri, maxSide = 1024)
                }
                if (path != null) {
                    com.miniichatNext.carter.debug.DebugLog.i(
                        "AvatarPicker", "crop unavailable, imported original image: $path"
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.avatar_crop_fallback_original),
                        Toast.LENGTH_SHORT
                    ).show()
                    onChange(Avatar.Image(path))
                } else {
                    com.miniichatNext.carter.debug.DebugLog.e(
                        "AvatarPicker", "import original image failed: $uri"
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.avatar_import_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val cropLauncher = rememberImageCropLauncher(
        onResult = { path ->
            val previousPath: String? = (current as? Avatar.Image)?.path
            scope.launch {
                val savedPath = if (canonicalPath != null) {
                    AvatarStorage.saveFileToPath(context, path, canonicalPath)
                } else if (AvatarStorage.isInAppStorage(context, path)) {
                    path
                } else {
                    AvatarStorage.saveFileToPath(
                        context,
                        path,
                        AvatarStorage.file(context, newId).absolutePath,
                    )
                }
                if (savedPath != null) {
                    DebugLog.i("AvatarPicker", "avatar saved: source=$path -> target=$savedPath")
                    onChange(Avatar.Image(savedPath))
                    if (path != savedPath) runCatching { java.io.File(path).delete() }
                    if (previousPath != null && previousPath != savedPath) {
                        kotlinx.coroutines.delay(30_000L)
                        runCatching { java.io.File(previousPath).delete() }
                    }
                } else {
                    DebugLog.e("AvatarPicker", "avatar save failed: source=$path")
                    Toast.makeText(
                        context,
                        context.getString(R.string.avatar_transfer_failed, "decode or save failed"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        },
        onCancel = {},
        onFailure = importOriginalImage,
        aspectRatio = 1f to 1f,
        outputDir = AvatarStorage.dir(context),
        outputPrefix = "avatar_",
        maxResultSize = 1024
    )

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        lastPickedUri = uri
        cropLauncher(uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.avatar_picker_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarView(
                            avatar = current,
                            fallbackInitial = "?",
                            size = 72.dp
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        PickerButton(
                            icon = Icons.Default.Image,
                            label = stringResource(R.string.avatar_picker_pick_image),
                            onClick = {
                                imagePicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        PickerButton(
                            icon = Icons.Default.Restore,
                            label = stringResource(R.string.avatar_picker_reset_emoji),
                            onClick = {
                                onChange(Avatar.Emoji("🙂"))
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.avatar_picker_emoji_hint), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = emojiDraft,
                        onValueChange = { emojiDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current,
                            fontSize = 18.sp
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val finalEmoji = emojiDraft.trim().ifBlank { "🙂" }
                onChange(Avatar.Emoji(finalEmoji))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )

}

@Composable
private fun PickerButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
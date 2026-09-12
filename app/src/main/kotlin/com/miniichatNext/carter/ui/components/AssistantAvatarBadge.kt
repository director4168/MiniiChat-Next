package com.miniichatNext.carter.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compact avatar badge for the drawer's "current assistant" row.
 *
 * - If [avatarPath] points to an existing image file, render it.
 * - Otherwise, fall back to an emoji (if [avatar] is non-blank)
 *   or to the first character of [fallbackName].
 */
@Composable
fun AssistantAvatarBadge(
    avatar: String?,
    avatarPath: String?,
    fallbackName: String?,
    size: Dp
) {
    val image: ImageBitmap? = if (!avatarPath.isNullOrBlank() && File(avatarPath).exists()) {
        val initial = remember(avatarPath) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(avatarPath) {
            initial.value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(avatarPath)?.asImageBitmap() }.getOrNull()
            }
        }
        initial.value
    } else null

    if (image != null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        val text = when {
            !avatar.isNullOrBlank() -> avatar
            !fallbackName.isNullOrBlank() -> fallbackName.take(1)
            else -> "?"
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = (size.value * 0.55f).sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

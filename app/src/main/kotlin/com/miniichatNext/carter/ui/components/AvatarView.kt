package com.miniichatNext.carter.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.Avatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AvatarView(
    avatar: Avatar,
    fallbackInitial: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        when (avatar) {
            is Avatar.Image -> {
                val bitmap = rememberBitmap(avatar.path)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(size),
                        contentScale = ContentScale.Crop
                    )
                } else if (fallbackInitial.isNotBlank()) {
                    Text(
                        text = fallbackInitial.take(1).uppercase(),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (size.value * 0.45f).sp,
                        color = LocalContentColor.current
                    )
                }
            }
            is Avatar.Emoji -> {
                Text(
                    text = avatar.content,
                    fontSize = (size.value * 0.55f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
            is Avatar.None -> {
                Text(
                    text = fallbackInitial.take(1).uppercase(),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.45f).sp,
                    color = LocalContentColor.current
                )
            }
        }
    }
}

@Composable
fun AvatarView(
    avatar: String,
    avatarPath: String?,
    fallbackInitial: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val a = Avatar.fromLegacy(avatar, avatarPath)
    AvatarView(a, fallbackInitial, modifier, size, background)
}

@Composable
private fun rememberBitmap(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank() || !java.io.File(path).exists()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

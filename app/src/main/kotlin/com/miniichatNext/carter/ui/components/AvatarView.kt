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
import kotlinx.coroutines.delay
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
        // 按实际显示尺寸采样解码：22dp 的列表头像没必要解码 4096px 的原图
        val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
        when (avatar) {
            is Avatar.Image -> {
                val bitmap = rememberBitmap(avatar.path, targetPx)
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
private fun rememberBitmap(path: String?, targetPx: Int): ImageBitmap? {
    var bitmap by remember(path, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, targetPx) {
        if (path.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val file = java.io.File(path)
        var decoded: ImageBitmap? = null
        // 重试 3 次：刚写盘的图片偶发"文件已存在但还没 flush 完"，
        // 之前这里一次失败就永久显示首字母，看起来就像"头像没生效"
        for (attempt in 0 until 3) {
            if (!file.exists() || file.length() <= 0L) {
                delay(140L)
                continue
            }
            decoded = withContext(Dispatchers.IO) {
                runCatching { decodeSampled(path, targetPx)?.asImageBitmap() }.getOrNull()
            }
            if (decoded != null) break
            delay(180L)
        }
        if (decoded == null) {
            if (!file.exists()) {
                com.miniichatNext.carter.Debug.DebugLog.w(
                    "AvatarView", "avatar file missing: $path"
                )
            } else {
                com.miniichatNext.carter.Debug.DebugLog.e(
                    "AvatarView", "avatar decode failed: $path (${file.length()} bytes, target=${targetPx}px)"
                )
            }
        } else {
            com.miniichatNext.carter.Debug.DebugLog.v(
                "AvatarView", "avatar loaded: $path (${file.length()} bytes, ${decoded.width}x${decoded.height})"
            )
        }
        bitmap = decoded
    }
    return bitmap
}

/**
 * 采样解码：按最长边不超过[targetPx]计算inSampleSize
 * 原实现直接decodeFile全尺寸图（头像裁剪原图可达12MP/48MB）
 * 低端机上decode返回null或OOM → 头像静默退回首字母
 */
private fun decodeSampled(path: String, targetPx: Int): android.graphics.Bitmap? {
    if (targetPx <= 0) return BitmapFactory.decodeFile(path)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx &&
        bounds.outHeight / (sample * 2) >= targetPx
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, options)
}

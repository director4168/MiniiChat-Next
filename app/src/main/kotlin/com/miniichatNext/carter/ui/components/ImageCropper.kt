package com.miniichatNext.carter.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miniichatNext.carter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Square-only avatar cropper.
 *
 * A fixed 1:1 crop window is centered on screen. The user pans / zooms the image
 * behind the window; the crop frame itself cannot be resized or reshaped.
 * Only the area inside the white square is kept.
 */
@Composable
fun ImageCropperDialog(
    sourceUri: Uri?,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    if (sourceUri == null) return
    val context = LocalContext.current
    var sourceBitmap by remember(sourceUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(sourceUri) {
        sourceBitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample = computeSample(bounds.outWidth, bounds.outHeight, 2048)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }?.asImageBitmap()
            }.getOrNull()
        }
    }

    val bitmap = sourceBitmap
    if (bitmap == null) {
        Dialog(onDismissRequest = onCancel) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.cropper_loading), color = Color.White)
            }
        }
        return
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CropperContent(
            bitmap = bitmap,
            onCancel = onCancel,
            onConfirm = onConfirm
        )
    }
}

@Composable
private fun CropperContent(
    bitmap: ImageBitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val minScale = 1f
    val maxScale = 8f

    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    val baseFit = remember(imgW, imgH, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) 1f
        else min(canvasSize.width / imgW, canvasSize.height / imgH)
    }

    // Fixed square crop window, centered.
    val cropSide = remember(canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) 0f
        else min(canvasSize.width, canvasSize.height).toFloat() - 32f
    }
    val crop = Rect(
        left = (canvasSize.width - cropSide) / 2f,
        top = (canvasSize.height - cropSide) / 2f,
        right = (canvasSize.width + cropSide) / 2f,
        bottom = (canvasSize.height + cropSide) / 2f
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .onSizeChanged { canvasSize = it }
        ) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(minScale, maxScale)
                            offset = Offset(offset.x + pan.x, offset.y + pan.y)
                        }
                    }
            )

            // Dim everything outside the fixed square.
            val dim = Color.Black.copy(alpha = 0.6f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = crop
                drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, c.top.coerceAtLeast(0f)))
                drawRect(
                    dim,
                    topLeft = Offset(0f, c.bottom),
                    size = Size(size.width, (size.height - c.bottom).coerceAtLeast(0f))
                )
                drawRect(
                    dim,
                    topLeft = Offset(0f, c.top.coerceAtLeast(0f)),
                    size = Size(c.left.coerceAtLeast(0f), (c.bottom - c.top).coerceAtLeast(0f))
                )
                drawRect(
                    dim,
                    topLeft = Offset(c.right, c.top.coerceAtLeast(0f)),
                    size = Size(
                        (size.width - c.right).coerceAtLeast(0f),
                        (c.bottom - c.top).coerceAtLeast(0f)
                    )
                )
                // crop border
                drawRect(
                    color = Color.White,
                    topLeft = Offset(c.left, c.top),
                    size = Size(c.width, c.height),
                    style = Stroke(width = 2f)
                )
            }
        }

        // Bottom bar with clear confirm / cancel buttons (always visible).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101010))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.FilledTonalButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
            Text(
                stringResource(R.string.cropper_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
            androidx.compose.material3.Button(onClick = {
                val cropped = cropSquare(bitmap, scale, offset, baseFit, canvasSize, crop)
                if (cropped != null) onConfirm(cropped)
            }) {
                Text(stringResource(R.string.cropper_confirm))
            }
        }
    }
}

private fun cropSquare(
    bitmap: ImageBitmap,
    scale: Float,
    offset: Offset,
    baseFit: Float,
    canvasSize: IntSize,
    crop: Rect
): Bitmap? {
    if (canvasSize.width == 0 || canvasSize.height == 0) return null
    if (crop.width < 8f || crop.height < 8f) return null
    val src = bitmap.asAndroidBitmap()

    val finalScale = scale * baseFit
    val displayedW = src.width * finalScale
    val displayedH = src.height * finalScale
    val cx = canvasSize.width / 2f + offset.x
    val cy = canvasSize.height / 2f + offset.y
    val left = cx - displayedW / 2f
    val top = cy - displayedH / 2f

    val intersectLeft = max(left, crop.left)
    val intersectTop = max(top, crop.top)
    val intersectRight = min(left + displayedW, crop.right)
    val intersectBottom = min(top + displayedH, crop.bottom)
    if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return null

    val sx = (intersectLeft - left) / finalScale
    val sy = (intersectTop - top) / finalScale
    val sw = (intersectRight - intersectLeft) / finalScale
    val sh = (intersectBottom - intersectTop) / finalScale

    val x = sx.toInt().coerceIn(0, src.width - 1)
    val y = sy.toInt().coerceIn(0, src.height - 1)
    val w = sw.toInt().coerceIn(1, src.width - x)
    val h = sh.toInt().coerceIn(1, src.height - y)

    val cropped = Bitmap.createBitmap(src, x, y, w, h)

    // Output at a fixed avatar size.
    val target = 512
    val result = if (cropped.width != target) {
        Bitmap.createScaledBitmap(cropped, target, target, true)
            .also { if (it !== cropped) cropped.recycle() }
    } else cropped

    if (result !== src) src.recycle()
    return result
}

private fun computeSample(w: Int, h: Int, maxSide: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var sample = 1
    var cw = w
    var ch = h
    while (cw / 2 >= maxSide || ch / 2 >= maxSide) {
        sample *= 2
        cw /= 2
        ch /= 2
    }
    return sample
}

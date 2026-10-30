package com.miniichatNext.carter.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R

private const val LONG_PRESS_TRIGGER_MS = 200L

@Composable
fun AboutIconCard(
    onPressProgress: (elapsedMs: Long) -> Unit,
    onPressReleased: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(context) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val density = context.resources.displayMetrics.density
            val pixelSize = (92f * density).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, pixelSize, pixelSize)
                drawable.draw(canvas)
            }.asImageBitmap()
        }.getOrNull()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            val releasedEarly = kotlinx.coroutines.withTimeoutOrNull(LONG_PRESS_TRIGGER_MS) {
                                tryAwaitRelease()
                            } == true
                            if (!releasedEarly) {
                                onPressProgress(LONG_PRESS_TRIGGER_MS)
                                tryAwaitRelease()
                            }
                            onPressReleased()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(92.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Text(
            text = stringResource(R.string.app_name),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

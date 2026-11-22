/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ImagePreviewStorage
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@Composable
internal fun GeneratedImagePreview(url: String, description: String, modifier: Modifier = Modifier) = key(url) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(url) { mutableStateOf(false) }
    var open by remember(url) { mutableStateOf(false) }
    val image by produceState<java.io.File?>(null, url) {
        try {
            value = runInterruptible(Dispatchers.IO) {
                val file = ImagePreviewStorage.load(context.filesDir, url)
                val mime = ImagePreviewStorage.mime(file)
                if (mime == "image/svg+xml") {
                    require(file.length() <= 2 * 1024 * 1024) { "SVG image is too large" }
                } else if (!url.startsWith("file:", true) &&
                    !url.startsWith(com.miniichatNext.carter.data.model.GeneratedImageStore.PUBLIC_IMAGE_PREFIX, true) &&
                    !url.startsWith(com.miniichatNext.carter.data.model.GeneratedImageStore.LOCAL_IMAGE_PREFIX, true)) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Image dimensions exceed the preview limit" }
                }
                file
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            DebugLog.e("ImagePreview", "load failed: $url", failure)
            error = true
        }
    }
    val preview = image
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = image
        if (uri != null && file != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output -> ImagePreviewStorage.open(file).use { it.copyTo(output) } }
                        ?: error("Image destination is not writable")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = true
            }
        }
    }
    Column(modifier) {
        if (preview != null) {
            Box(Modifier.fillMaxWidth().clickable { open = true }) {
                ImagePreviewBody(
                    file = preview,
                    description = description,
                    zoom = false,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Box(Modifier.matchParentSize().clickable { open = true })
            }
        } else {
            Text(stringResource(if (error) R.string.generated_image_failed else R.string.generated_image_loading),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
    if (open && preview != null) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.back)) }
                    TextButton(onClick = { image?.let { save.launch("MiniiChat-image.${ImagePreviewStorage.extension(it)}") } }) {
                        Text(stringResource(R.string.save))
                    }
                }
                Text(description, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                ImagePreviewBody(
                    file = preview,
                    description = description,
                    zoom = true,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewBody(file: java.io.File, description: String, zoom: Boolean, modifier: Modifier) {
    val mime = remember(file) { ImagePreviewStorage.mime(file) }
    if (mime == "image/svg+xml") {
        StaticImageWebView(file, zoom = zoom, modifier = modifier)
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, file, zoom) {
        value = withContext(Dispatchers.IO) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val maxSide = if (zoom) 4096 else 1600
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier) {
            Text(
                text = stringResource(R.string.generated_image_loading),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun StaticImageWebView(file: java.io.File, zoom: Boolean, modifier: Modifier) = key(file, zoom) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = zoom
                settings.displayZoomControls = false
                settings.setSupportZoom(zoom)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.toString() != ImagePreviewStorage.DOCUMENT_URL

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url.toString()
                        if (url == ImagePreviewStorage.DOCUMENT_URL) {
                            return WebResourceResponse("text/html", "UTF-8", ImagePreviewStorage.HTML.byteInputStream())
                        }
                        if (url == ImagePreviewStorage.IMAGE_URL) {
                            val stream = runCatching { ImagePreviewStorage.open(file) }.getOrNull()
                            if (stream != null) return WebResourceResponse(ImagePreviewStorage.mime(file), null, stream)
                        }
                        return WebResourceResponse("text/plain", "UTF-8", byteArrayOf().inputStream())
                    }
                }
                loadUrl(ImagePreviewStorage.DOCUMENT_URL)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
        modifier = modifier,
    )
}

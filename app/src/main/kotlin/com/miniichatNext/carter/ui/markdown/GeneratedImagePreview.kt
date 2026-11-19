/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ImagePreviewStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GeneratedImagePreview(url: String, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(url) { mutableStateOf(false) }
    var open by remember(url) { mutableStateOf(false) }
    val image by produceState<java.io.File?>(null, url) {
        try {
            value = withContext(Dispatchers.IO) { ImagePreviewStorage.load(context.filesDir, url) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = true
        }
    }
    val html by produceState("", image) {
        val file = image ?: return@produceState
        value = withContext(Dispatchers.IO) { ImagePreviewStorage.html(file) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = image
        if (uri != null && file != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Image destination is not writable")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = true
            }
        }
    }
    Column(modifier) {
        if (html.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().clickable { open = true }) {
                StaticImageWebView(html, zoom = false, modifier = Modifier.fillMaxWidth().height(200.dp))
                Box(Modifier.matchParentSize().clickable { open = true })
            }
        } else {
            Text(stringResource(if (error) R.string.generated_image_failed else R.string.generated_image_loading),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
    if (open && html.isNotEmpty()) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.back)) }
                    TextButton(onClick = { image?.let { save.launch("MiniiChat-image.${ImagePreviewStorage.extension(it)}") } }) {
                        Text(stringResource(R.string.save))
                    }
                }
                Text(description, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                StaticImageWebView(html, zoom = true, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun StaticImageWebView(html: String, zoom: Boolean, modifier: Modifier) {
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
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL("https://miniichat.local/", html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
        modifier = modifier,
    )
}

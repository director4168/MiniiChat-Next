package com.miniichatNext.carter.ui.settings

import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

@Composable
fun EasterEggOverlay(
    elapsedMs: Long,
    closingElapsedMs: Long,
    onDismiss: () -> Unit,
) {
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val closeProgress = (closingElapsedMs / 250f).coerceIn(0f, 1f)


    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.stopLoading()
            webViewState.value?.destroy()
            webViewState.value = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    webViewState.value = this
                    visibility = View.VISIBLE
                    alpha = 1f
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(AndroidColor.BLACK)
                    setWebViewClient(WebViewClient())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = true
                    settings.loadWithOverviewMode = false
                    settings.useWideViewPort = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    loadUrl("file:///android_asset/starfield.html")
                }
            },
        )



        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(4f)
                .background(Color.Black.copy(alpha = closeProgress))
                .clickable(onClick = onDismiss),
        )
    }
}

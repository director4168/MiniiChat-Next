package com.miniichatNext.carter.ui.markdown

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import com.miniichatNext.carter.R
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
) {
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    val colorScheme = MaterialTheme.colorScheme
    val palette = remember(colorScheme) {
        InlinePalette(
            codeBg = colorScheme.surfaceContainer,
            codeFg = colorScheme.onSurface,
            langFg = colorScheme.onSurfaceVariant,
            link = colorScheme.primary,
            quoteBar = colorScheme.outline,
            quoteFg = colorScheme.onSurfaceVariant,
            divider = colorScheme.outline,
            headerBg = colorScheme.surfaceContainerHigh,
            headerFg = colorScheme.onSurface,
            cellFg = colorScheme.onSurface,
            outline = colorScheme.outline,
        )
    }

    val blocks = remember(text) { parseBlocks(text) }
    MarkdownBlocks(blocks, palette, color) { pendingUrl = it }

    pendingUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text(stringResource(R.string.markdown_open_link_title)) },
            text = {
                Column {
                    Text(url, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.markdown_open_link_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUrl = null
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        Toast.makeText(ctx, R.string.markdown_open_link_failed, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.open)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

package com.miniichatNext.carter.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
internal fun parseInline(
    text: String,
    palette: InlinePalette,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)

        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                pushStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = palette.codeBg,
                    color = palette.codeFg,
                    fontSize = 14.sp
                ))
                append(text.substring(i + 1, end)); pop()
                i = end + 1; continue
            }
        }

        if (rest.startsWith("**")) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(text.substring(i + 2, end)); pop()
                i = end + 2; continue
            }
        }

        if (rest.startsWith("~~")) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(text.substring(i + 2, end)); pop()
                i = end + 2; continue
            }
        }

        if ((text[i] == '*' && !rest.startsWith("**")) ||
            (text[i] == '_' && !rest.startsWith("__"))) {
            val ch = text[i]
            val end = text.indexOf(ch, i + 1)
            if (end > i + 1 && (end + 1 >= text.length || text[end + 1] != ch)) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(text.substring(i + 1, end)); pop()
                i = end + 1; continue
            }
        }

        if (text[i] == '[') {
            val close = text.indexOf(']', i + 1)
            if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                val urlEnd = text.indexOf(')', close + 2)
                if (urlEnd > close + 1) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, urlEnd)
                    val link = LinkAnnotation.Clickable(
                        tag = "url",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = palette.link,
                                textDecoration = TextDecoration.Underline
                            ),
                            hoveredStyle = SpanStyle(
                                color = palette.link,
                                textDecoration = TextDecoration.Underline,
                                background = palette.codeBg
                            ),
                            pressedStyle = SpanStyle(
                                color = palette.link,
                                textDecoration = TextDecoration.Underline,
                                background = palette.codeBg
                            ),
                        ),
                        linkInteractionListener = { onLink(url) }
                    )
                    withLink(link) { append(label) }
                    i = urlEnd + 1; continue
                }
            }
        }

        append(text[i]); i++
    }
}

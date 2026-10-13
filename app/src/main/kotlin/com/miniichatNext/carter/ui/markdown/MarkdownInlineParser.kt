package com.miniichatNext.carter.ui.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse

internal data class InlineRendered(
    val text: AnnotatedString,
    val contents: Map<String, InlineTextContent>,
)

@OptIn(ExperimentalTextApi::class)
@Composable
internal fun parseInline(
    text: String,
    palette: InlinePalette,
    onLink: (String) -> Unit,
    fontSizePx: Float = 16f,
    color: Color = Color.Unspecified,
): InlineRendered {
    val density = LocalDensity.current
    val contents = mutableMapOf<String, InlineTextContent>()
    var mathSeq = 0
    val mathSlots = mutableListOf<Triple<String, String, Rect>>()

    // 找单字符标记（*/_）闭合位置：跳过长度>=2的连续run（它们属于嵌套的**/__标记）
    fun findSingleClose(s: String, from: Int, ch: Char): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == ch) {
                var run = 1
                while (k + run < s.length && s[k + run] == ch) run++
                // run>=2：取run的第一个星作为斜体闭合（其余星留给外层**/更外层消耗）。
                // 取最后一个会让*斜体里面嵌套**加粗**...*的嵌套粗体星号原样暴露。
                // 延伸到串尾（*斜体***）同理闭第一个星，剩余星按普通字符原样。
                if (run >= 2) {
                    val close = k
                    if (close > 0 && s[close - 1].isWhitespace()) { k += run; continue }
                    return close
                }
                // 闭标记前一字符不能是空白
                if (k > 0 && s[k - 1].isWhitespace()) { k++; continue }
                return k
            } else {
                k++
            }
        }
        return -1
    }

    // 找多字符标记的闭合：要求闭合位置不在连续run里
    // 例如***闭合不能落在*****中间（避免***互相误闭合）
    // 例如**闭合不能落在***前两个*上（避免***把**的闭合误吃）
    fun findBoldClose(s: String, from: Int, openFrom: Int): Int {
        var k = from
        while (k < s.length - 1) {
            if (s[k] == '*' && s[k + 1] == '*') {
                // 闭**前一字符不能是空白（CommonMark右翼规则）
                if (k > openFrom + 1 && s[k - 1].isWhitespace()) { k += 2; continue }
                // 闭合在***上：若内容里有未闭合的单*（斜体开头），取***的后2个*
                // 让内层斜体先用第1个*闭合，外层**吃后2个
                if (k + 2 < s.length && s[k + 2] == '*') {
                    val seg = s.substring(openFrom, k)
                    var hasSingle = false
                    var j = 0
                    while (j < seg.length) {
                        val prevOk = j == 0 || seg[j - 1] != '*'
                        val nextOk = j + 1 >= seg.length || seg[j + 1] != '*'
                        if (seg[j] == '*' && prevOk && nextOk) { hasSingle = true; break }
                        j++
                    }
                    if (hasSingle) { k++; continue }
                    return k
                }
                return k
            }
            k++
        }
        return -1
    }

    fun findTripleClose(s: String, from: Int): Int {
        var k = from
        while (k < s.length - 2) {
            if (s[k] == '*' && s[k + 1] == '*' && s[k + 2] == '*') return k
            k++
        }
        return -1
    }


    val builder = AnnotatedString.Builder()

    // 行内公式：\(..\)/ $$..$$ / $..$；成功返回新扫描位置，失败返回-1
    fun appendMath(s: String, start: Int): Int {
        var mathFrom = -1
        var mathTo = -1
        var mathNext = -1
        val rest = s.substring(start)
        if (rest.startsWith("\\(")) {
            mathFrom = start + 2
            mathTo = s.indexOf("\\)", mathFrom)
            mathNext = mathTo + 2
        } else if (rest.startsWith("$$")) {
            mathFrom = start + 2
            mathTo = s.indexOf("$$", mathFrom)
            mathNext = mathTo + 2
        } else if (s[start] == '$' && (start == 0 || s[start - 1] != '\\') &&
            start + 1 < s.length && s[start + 1] != '$'
        ) {
            mathFrom = start + 1
            mathTo = s.indexOf('$', mathFrom)
            mathNext = mathTo + 1
        }
        if (mathFrom >= 0 && mathTo > mathFrom) {
            val latex = s.substring(mathFrom, mathTo)
            val rect = assumeLatexSize(latex, fontSizePx)
            // 测不出尺寸说明公式解析失败→按普通文本处理（避免0尺寸占位符）
            if (rect.width() > 0 && rect.height() > 0) {
                val id = "md-math-${mathSeq++}"
                mathSlots += Triple(id, latex, rect)
                builder.appendInlineContent(id, alternateText = latex)
                return mathNext
            }
        }
        return -1
    }

    // 递归解析：粗体/斜体/粗斜体/删除线/行内代码/链接/公式任意嵌套
    fun parse(s: String) {
        var i = 0
        while (i < s.length) {
            val rest = s.substring(i)

            if (s[i] == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    builder.pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = palette.inlineCodeBg,
                        color = palette.inlineCodeFg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ))
                    builder.append(s.substring(i + 1, end)); builder.pop()
                    i = end + 1; continue
                }
            }

            // ***粗斜体***（必须在**之前匹配）——跳过连续4+个*的位置避免误闭合
            if (rest.startsWith("***")) {
                val end = findTripleClose(s, i + 3)
                if (end > i + 3) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    parse(s.substring(i + 3, end)); builder.pop()
                    i = end + 3; continue
                }
            }

            if (rest.startsWith("**")) {
                // 开**后不能是空白/行尾（左翼规则）
                if (i + 2 >= s.length || s[i + 2].isWhitespace() || s[i + 2] == '\n') {
                    builder.append(s.substring(i, i + 2)); i += 2; continue
                }
                val end = findBoldClose(s, i + 2, i)
                if (end > i + 2) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    parse(s.substring(i + 2, end)); builder.pop()
                    i = end + 2; continue
                }
            }

            if (rest.startsWith("~~")) {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    parse(s.substring(i + 2, end)); builder.pop()
                    i = end + 2; continue
                }
            }

            // *斜体* / _斜体_（跳过嵌套的**/__ run）
            if (s[i] == '*' || s[i] == '_') {
                val ch = s[i]
                // 开标记后不能是空白/行尾（CommonMark左翼规则）：* X *不是强调
                if (i + 1 >= s.length || s[i + 1].isWhitespace() || s[i + 1] == '\n') {
                    builder.append(s[i]); i++; continue
                }
                // 词内下划线豁免：snake_case_name的_不当斜体标记
                if (ch == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    && i + 1 < s.length && s[i + 1].isLetterOrDigit()
                ) {
                    builder.append(s[i]); i++; continue
                }
                val end = findSingleClose(s, i + 1, ch)
                if (end > i + 1) {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    parse(s.substring(i + 1, end)); builder.pop()
                    i = end + 1; continue
                }
            }

            // 行内公式
            if (rest.startsWith("\\(") || rest.startsWith("$$") || s[i] == '$') {
                val next = appendMath(s, i)
                if (next > i) {
                    i = next; continue
                }
            }

            // 图片语法![](url)：跳过!（按链接处理）
            if (s[i] == '!' && i + 1 < s.length && s[i + 1] == '[') {
                i++
                continue
            }

            if (s[i] == '[') {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                    val urlEnd = s.indexOf(')', close + 2)
                    if (urlEnd > close + 1) {
                        val label = s.substring(i + 1, close)
                        val url = s.substring(close + 2, urlEnd)
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
                        builder.withLink(link) { parse(label) }
                        i = urlEnd + 1; continue
                    }
                }
            }

            builder.append(s[i]); i++
        }
    }

    parse(text)
    // 公式占位符的InlineTextContent在Composable上下文统一构造
    for ((id, latex, rect) in mathSlots) {
        contents[id] = InlineTextContent(
            Placeholder(
                width = with(density) { rect.width().toSp() },
                height = with(density) { rect.height().toSp() },
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) { LatexText(latex, color = color) }
    }
    return InlineRendered(builder.toAnnotatedString(), contents)
}

@Composable
internal fun InlineMarkdownText(
    text: String,
    palette: InlinePalette,
    onLink: (String) -> Unit,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { style.fontSize.takeOrElse { 16.sp }.toPx() }
    val rendered = parseInline(text, palette, onLink, fontSizePx, color)
    Text(
        text = rendered.text,
        modifier = modifier,
        color = color,
        style = style,
        inlineContent = rendered.contents,
    )
}

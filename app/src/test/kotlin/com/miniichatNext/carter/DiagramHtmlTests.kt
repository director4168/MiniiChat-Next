/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.ui.markdown.buildDiagramHtml
import com.miniichatNext.carter.ui.markdown.normalizedSvg
import org.junit.Assert.*
import org.junit.Test

class DiagramHtmlTests {
    private val namespace = "http:" + "//www.w3.org/2000/svg"
    private val fileUri = "file:" + "///data/"
    private val external = "https:" + "//example.com"
    @Test fun svgWithoutNamespaceRendersInlineAndGetsAViewBox() {
        val svg = """<svg width="320" height="180"><rect width="320" height="180" fill="red"/><text x="20" y="40">测试</text></svg>"""
        val html = buildDiagramHtml(svg, svg = true, dark = false)
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("xmlns=\"$namespace\""))
        assertTrue(html.contains("viewBox=\"0 0 320.0 180.0\""))
        assertTrue(html.contains("测试"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("src=\"data:image"))
        assertFalse(html.contains("<script"))
    }

    @Test fun gradientsAndViewBoxKeepTheirCaseAndLocalReferences() {
        val svg = """<svg xmlns="${namespace}" viewBox="0 0 40 40"><defs><linearGradient id="grad"><stop offset="0" stop-color="red"/></linearGradient></defs><rect width="40" height="40" fill="url(#grad)"/></svg>"""
        val normalized = normalizedSvg(svg)
        assertTrue(normalized.contains("linearGradient"))
        assertTrue(normalized.contains("viewBox=\"0 0 40 40\""))
        assertTrue(normalized.contains("url(#grad)"))
    }

    @Test fun untrustedSvgNeverAddsScriptOrExternalNavigation() {
        val svg = """<svg onload="alert(1)"><script>alert(2)</script><a href="${external}"><text>test</text></a><foreignObject><iframe src="${fileUri}"/></foreignObject><image href="${external}/a.png"/></svg>"""
        val result = normalizedSvg(svg)
        assertFalse(result.contains("onload"))
        assertFalse(result.contains("<script"))
        assertFalse(result.contains("foreignObject"))
        assertFalse(result.contains("https://"))
        assertFalse(result.contains("file://"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun svgDoctypeIsRejectedBeforeParsing() {
        normalizedSvg("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:/// etc/passwd">]><svg>&x;</svg>""")
    }

    @Test fun mermaidUsesOneLocalBundleAndEscapesUserSource() {
        val html = buildDiagramHtml("graph TD; A-->B\n</pre><script>alert(1)</script>", svg = false, dark = true)
        assertTrue(html.contains("await mermaid.run"))
        assertTrue(html.contains("https:" + "//miniichat.local/assets/html/mermaid.min.js"))
        assertTrue(html.contains("securityLevel:'strict'"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("noteBorderColor"))
        assertFalse(html.contains("minScale"))
    }

    @Test fun fullscreenSupportsZoomAndScrollRatherThanCardHeightClipping() {
        val html = buildDiagramHtml("graph TD; A-->B", svg = false, dark = false, fullscreen = true)
        assertTrue(html.contains("user-scalable=yes"))
        assertTrue(html.contains("body{overflow:auto;"))
        assertFalse(html.contains("176"))
        assertFalse(html.contains("200px"))
    }

    @Test fun androidViewportKeepsSvgCanvasHeightIndependentOfWrapContent() {
        val source = """<svg width="512" height="512" viewBox="0 0 512 512"><rect x="0" y="0" width="512" height="512" fill="#FF0000"/></svg>"""
        val html = buildDiagramHtml(source, svg = true, dark = false)
        assertTrue(html.contains("height:100vh;min-height:100vh"))
        assertTrue(html.contains("fill=\"#FF0000\""))
        assertTrue(html.contains("viewBox=\"0 0 512 512\""))
        assertFalse(html.contains("&lt;svg"))
    }

}

/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.markdown

import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** SVG直接嵌入HTML；不依赖图片data URI的命名空间和固有尺寸推断。 */
internal fun normalizedSvg(code: String): String {
    require(code.length <= 2 * 1024 * 1024) { "SVG source is too large" }
    require(!code.contains("<!DOCTYPE", true) && !code.contains("<!ENTITY", true)) { "SVG declarations are not supported" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // 部分Android XML实现不支持这两个feature；DOCTYPE已在解析前被拒绝。
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
    var input = code.trim().removePrefix("\uFEFF")
    val opening = Regex("""<svg\b[^>]*>""").find(input)
    if (opening != null && !Regex("""\bxmlns\s*=""").containsMatchIn(opening.value)) {
        input = input.replaceRange(opening.range, opening.value.replaceFirst("<svg", "<svg xmlns=\"http://www.w3.org/2000/svg\""))
    }
    val document = builder.parse(InputSource(StringReader(input)))
    val root = document.documentElement
    require(root.localName == "svg") { "Expected an SVG root element" }
    fun clean(element: Element) {
        for (index in element.attributes.length - 1 downTo 0) {
            val attr = element.attributes.item(index)
            val name = attr.nodeName.lowercase()
            val value = attr.nodeValue.trim()
            if (name.startsWith("on") || name in setOf("src", "xml:base") ||
                (name in setOf("href", "xlink:href") && !value.startsWith("#") && !value.startsWith("data:image/", true))) {
                element.removeAttribute(attr.nodeName)
            }
        }
        val children = (0 until element.childNodes.length).map { element.childNodes.item(it) }
        children.forEach { child ->
            if (child is Element) {
                if (child.localName.lowercase() in setOf("script", "foreignobject", "iframe", "object", "embed", "animate", "set", "animatetransform", "animatemotion")) {
                    element.removeChild(child)
                } else clean(child)
            }
        }
    }
    clean(root)
    if (!root.hasAttribute("viewBox")) {
        val width = root.getAttribute("width").removeSuffix("px").toDoubleOrNull()
        val height = root.getAttribute("height").removeSuffix("px").toDoubleOrNull()
        if (width != null && height != null && width > 0 && height > 0) root.setAttribute("viewBox", "0 0 $width $height")
    }
    val writer = StringWriter()
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    }.transform(DOMSource(root), StreamResult(writer))
    return writer.toString()
}

/** 结构适配自rikkahub的CodeBlockPreview与Mermaid，JS资源只从应用assets加载。 */
internal fun buildDiagramHtml(
    code: String,
    svg: Boolean,
    dark: Boolean,
    background: String = if (dark) "#23262e" else "#ffffff",
    foreground: String = if (dark) "#e8edf5" else "#202a3c",
    fullscreen: Boolean = false,
): String {
    val svgMarkup = if (svg) normalizedSvg(code) else ""
    val escaped = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val content = if (svg) svgMarkup else "<pre class=\"mermaid\">$escaped</pre>"
    val script = if (svg) "" else """
<script src="https://miniichat.local/assets/html/mermaid.min.js"></script>
<script>
(async function() {
  try {
    mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'${if (dark) "dark" else "default"}'});
    await mermaid.run({querySelector:'.mermaid'});
    var svg = document.querySelector('#diagram svg');
    if (!svg) throw new Error('No diagram was produced');
    var box = svg.viewBox && svg.viewBox.baseVal;
    if (box && box.width > 0 && box.height > 0) {
      svg.setAttribute('width', box.width);
      svg.setAttribute('height', box.height);
    }
  } catch (error) {
    document.getElementById('diagram').textContent = error.message || String(error);
  }
})();
</script>
"""
    return """
<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=10,user-scalable=yes">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://miniichat.local; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;min-height:100vh;background:$background;color:$foreground;font-family:sans-serif;}
body{overflow:${if (fullscreen) "auto" else "hidden"};}
#diagram{box-sizing:border-box;width:100%;height:100vh;min-height:100vh;padding:12px;display:flex;align-items:center;justify-content:center;}
.mermaid{margin:0;width:100%;height:100%;min-height:0;display:flex;align-items:center;justify-content:center;}
#diagram svg{display:block;max-width:100%!important;max-height:100%!important;${if (svg) "width:100%;height:100%;" else "width:auto;height:auto;"}}
</style></head><body><div id="diagram">$content</div>$script</body></html>
""".trimIndent()
}

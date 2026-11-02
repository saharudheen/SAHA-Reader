package com.saha.reader

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Converts a .docx file into simple HTML: paragraphs, headings, bold/italic, lists, tables and images. */
object DocxParser {

    private class Para {
        val sb = StringBuilder()
        var style = ""
        var align = ""
        var list = false

        fun render(): String {
            val s = style.lowercase()
            val tag = when {
                s == "title" -> "h1"
                s.startsWith("heading1") || s == "heading 1" -> "h1"
                s.startsWith("heading2") || s == "heading 2" -> "h2"
                s.startsWith("heading") || s == "subtitle" -> "h3"
                else -> "p"
            }
            val a = when (align) {
                "center" -> "center"
                "right", "end" -> "right"
                "both", "distribute" -> "justify"
                else -> ""
            }
            val styleAttr = if (a.isNotEmpty()) " style='text-align:$a'" else ""
            if (sb.isEmpty() && tag == "p") return "<p class='e'></p>"
            val body = if (list || s.contains("list")) "• $sb" else sb.toString()
            return "<$tag$styleAttr>$body</$tag>"
        }
    }

    fun toHtml(input: InputStream): String {
        val files = XmlKit.readZip(input) {
            it == "word/document.xml" || it == "word/_rels/document.xml.rels" || it.startsWith("word/media/")
        }
        val doc = files["word/document.xml"]
            ?: throw IllegalArgumentException("This isn't a valid Word (.docx) file.")
        val rels = XmlKit.parseRels(files["word/_rels/document.xml.rels"])

        val out = StringBuilder()
        val paras = ArrayList<Para>()
        fun sink(): StringBuilder = if (paras.isEmpty()) out else paras[paras.size - 1].sb

        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var inTabs = false
        var skip = 0 // inside mc:Fallback (duplicate content)

        fun on(p: XmlPullParser): Boolean {
            val v = XmlKit.attr(p, "w:val")
            return v == null || (v != "0" && v != "false" && v != "none")
        }

        fun image(relId: String?) {
            val target = rels[relId ?: return] ?: return
            val path = XmlKit.resolve("word/", target)
            val bytes = files[path] ?: return
            val mime = when (path.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                else -> return // emf/wmf etc. can't be shown
            }
            sink().append("<img src='data:").append(mime).append(";base64,")
                .append(Base64.encodeToString(bytes, Base64.NO_WRAP)).append("'>")
        }

        val p = XmlKit.parser(doc)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val name = p.name
                if (name == "mc:Fallback") skip++
                if (skip == 0) when (name) {
                    "w:p" -> paras.add(Para())
                    "w:pStyle" -> paras.lastOrNull()?.style = XmlKit.attr(p, "w:val") ?: ""
                    "w:jc" -> paras.lastOrNull()?.align = XmlKit.attr(p, "w:val") ?: ""
                    "w:numPr" -> paras.lastOrNull()?.list = true
                    "w:r" -> { bold = false; italic = false; underline = false; strike = false }
                    "w:b" -> bold = on(p)
                    "w:i" -> italic = on(p)
                    "w:u" -> underline = on(p)
                    "w:strike", "w:dstrike" -> strike = on(p)
                    "w:tabs" -> inTabs = true
                    "w:tab" -> if (!inTabs) sink().append("&emsp;")
                    "w:br" -> sink().append(if (XmlKit.attr(p, "w:type") == "page") "<hr>" else "<br>")
                    "w:cr" -> sink().append("<br>")
                    "w:tbl" -> sink().append("<div class='tw'><table>")
                    "w:tr" -> sink().append("<tr>")
                    "w:tc" -> sink().append("<td>")
                    "a:blip" -> image(XmlKit.attr(p, "r:embed"))
                    "v:imagedata" -> image(XmlKit.attr(p, "r:id"))
                    "w:t" -> {
                        var t = HtmlKit.esc(p.nextText())
                        if (t.isNotEmpty()) {
                            if (bold) t = "<b>$t</b>"
                            if (italic) t = "<i>$t</i>"
                            if (underline) t = "<u>$t</u>"
                            if (strike) t = "<s>$t</s>"
                            sink().append(t)
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val name = p.name
                if (name == "mc:Fallback") {
                    skip--
                } else if (skip == 0) when (name) {
                    "w:p" -> if (paras.isNotEmpty()) {
                        val para = paras.removeAt(paras.size - 1)
                        sink().append(para.render())
                    }
                    "w:tabs" -> inTabs = false
                    "w:tc" -> sink().append("</td>")
                    "w:tr" -> sink().append("</tr>")
                    "w:tbl" -> sink().append("</table></div>")
                }
            }
            ev = p.next()
        }
        if (out.isBlank()) return HtmlKit.message("Empty document", "This Word file has no text.")
        return HtmlKit.page(out.toString())
    }
}

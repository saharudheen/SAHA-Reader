package com.saha.reader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Shared helpers for building the HTML pages shown in the WebView. */
object HtmlKit {
    private const val BASE_CSS = """
body{font-family:sans-serif;margin:0;padding:16px;color:#202124;background:#fff;line-height:1.5;word-wrap:break-word}
img{max-width:100%;height:auto}
h1{font-size:1.6em;margin:.6em 0 .3em}h2{font-size:1.3em;margin:.6em 0 .3em}h3{font-size:1.1em;margin:.6em 0 .3em}
p{margin:0 0 8px;white-space:pre-wrap}
p.e{margin:0;min-height:1em}
.tw{overflow-x:auto;margin:8px 0}
.tw table{border-collapse:collapse}
.tw td{border:1px solid #c8c8c8;padding:4px 8px;vertical-align:top;min-width:40px}
.tw td p{margin:0}
pre{white-space:pre-wrap;word-wrap:break-word;font-family:monospace;font-size:14px;margin:0}
.msg{text-align:center;color:#444;padding:48px 16px;font-size:16px}
.msg b{display:block;font-size:20px;margin-bottom:12px;color:#202124}
.note{background:#FFF4E5;color:#7A4B00;padding:8px 12px;border-radius:8px;margin:8px 0;font-size:13px}
body.grid{padding:0}
.tabs{position:sticky;top:0;z-index:5;height:42px;background:#f1f3f4;white-space:nowrap;overflow-x:auto;border-bottom:1px solid #ccc}
.tabs button{border:0;background:none;padding:0 16px;height:42px;font-size:14px;color:#444}
.tabs button.on{color:#1D7A45;font-weight:bold;border-bottom:3px solid #1D7A45}
.sheet{display:none}
.sheet.on{display:block}
table.g{border-collapse:separate;border-spacing:0;font-size:13px}
table.g td{border-right:1px solid #e0e0e0;border-bottom:1px solid #e0e0e0;padding:3px 6px;white-space:nowrap;max-width:340px;overflow:hidden;text-overflow:ellipsis;background:#fff}
table.g td.n{text-align:right}
table.g th{background:#f3f3f3;color:#666;font-weight:normal;border-right:1px solid #d0d0d0;border-bottom:1px solid #d0d0d0;padding:3px 6px}
table.g thead th{position:sticky;top:0;z-index:2}
body.tabbed table.g thead th{top:42px}
table.g th.r{position:sticky;left:0;z-index:1;text-align:right}
table.g thead th.r{z-index:3}
"""

    fun page(body: String, bodyClass: String = "", script: String = ""): String =
        "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=6, user-scalable=yes'>" +
            "<style>$BASE_CSS</style></head><body class='$bodyClass'>$body" +
            (if (script.isNotEmpty()) "<script>$script</script>" else "") + "</body></html>"

    fun message(title: String, text: String): String =
        page("<div class='msg'><b>${esc(title)}</b>${esc(text)}</div>")

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private val numberRegex = Regex("^[-+]?[₹$€£]?\\s?[\\d,]*\\.?\\d+%?$")
    fun looksNumeric(s: String) = s.isNotEmpty() && s.length < 40 && numberRegex.matches(s.trim())

    class Row(val num: Int, val cells: List<String>)

    /** Spreadsheet-style grid with column letters and row numbers. */
    fun grid(sb: StringBuilder, rows: List<Row>, maxCols: Int) {
        sb.append("<table class='g'><thead><tr><th class='r'></th>")
        for (c in 0 until maxCols) sb.append("<th>").append(colName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        for (r in rows) {
            sb.append("<tr><th class='r'>").append(r.num).append("</th>")
            for (c in 0 until maxCols) {
                val t = if (c < r.cells.size) r.cells[c] else ""
                sb.append(if (looksNumeric(t)) "<td class='n'>" else "<td>").append(esc(t)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
    }
}

object XmlKit {
    fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), null)
    }

    fun local(name: String?): String = name?.substringAfter(':') ?: ""

    fun attr(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)

    /** Reads entries of a .docx/.xlsx (zip) that match [want]. */
    fun readZip(input: InputStream, want: (String) -> Boolean): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name.trimStart('/')
                if (!e.isDirectory && want(name)) map[name] = z.readBytes()
                e = z.nextEntry
            }
        }
        if (map.isEmpty()) throw IllegalArgumentException(
            "This file isn't a valid Office document. It may be damaged or password protected."
        )
        return map
    }

    /** Relationship Id -> Target from a .rels file. */
    fun parseRels(bytes: ByteArray?): Map<String, String> {
        val out = HashMap<String, String>()
        if (bytes == null) return out
        val p = parser(bytes)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                val id = attr(p, "Id")
                val target = attr(p, "Target")
                if (id != null && target != null) out[id] = target
            }
            ev = p.next()
        }
        return out
    }

    /** Resolves a relationship target relative to a folder like "xl/". */
    fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val parts = ArrayList<String>()
        for (seg in (baseDir + target).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}

object IoKit {
    /** Reads at most [limit] bytes. Returns the bytes and whether the file was cut short. */
    fun readLimited(input: InputStream, limit: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > limit) {
                out.write(buf, 0, limit - total)
                return Pair(out.toByteArray(), true)
            }
            out.write(buf, 0, n)
            total += n
        }
        return Pair(out.toByteArray(), false)
    }

    private fun b(v: Int) = v.toByte()

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == b(0xEF) && bytes[1] == b(0xBB) && bytes[2] == b(0xBF))
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == b(0xFF) && bytes[1] == b(0xFE))
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == b(0xFE) && bytes[1] == b(0xFF))
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return String(bytes, Charsets.UTF_8)
    }

    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.size >= 2 && (bytes[0] == b(0xFF) && bytes[1] == b(0xFE) || bytes[0] == b(0xFE) && bytes[1] == b(0xFF))) return false
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }
}

package com.saha.reader

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Converts an .xlsx workbook into HTML with one tab per sheet. */
object XlsxParser {
    private const val MAX_ROWS = 10000
    private const val MAX_COLS = 200

    private enum class Kind { GENERAL, DATE, TIME, DATETIME, PERCENT }

    private val dateFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
    private val excelEpoch: LocalDateTime = LocalDateTime.of(1899, 12, 30, 0, 0)

    fun toHtml(input: InputStream): String {
        val files = XmlKit.readZip(input) { it.startsWith("xl/") && (it.endsWith(".xml") || it.endsWith(".rels")) }
        val wb = files["xl/workbook.xml"] ?: throw IllegalArgumentException("This isn't a valid Excel (.xlsx) file.")
        val shared = parseShared(files["xl/sharedStrings.xml"])
        val styles = parseStyles(files["xl/styles.xml"])
        val rels = XmlKit.parseRels(files["xl/_rels/workbook.xml.rels"])

        val tabs = StringBuilder()
        val body = StringBuilder()
        var k = 0
        val sheets = parseWorkbook(wb)
        for ((name, rid) in sheets) {
            val target = rels[rid] ?: continue
            val data = files[XmlKit.resolve("xl/", target)] ?: continue
            val sheet = parseSheet(data, shared, styles)
            val on = if (k == 0) " on" else ""
            tabs.append("<button class='t$on' onclick='show($k)'>").append(HtmlKit.esc(name)).append("</button>")
            body.append("<div class='sheet$on' id='s$k'>")
            if (sheet.truncated) body.append("<div class='note'>Showing first $MAX_ROWS rows only.</div>")
            if (sheet.rows.isEmpty()) body.append("<div class='msg'>This sheet is empty.</div>")
            else HtmlKit.grid(body, sheet.rows, sheet.maxCols)
            body.append("</div>")
            k++
        }
        if (k == 0) return HtmlKit.message("Empty workbook", "No sheets could be read from this file.")
        val script = """
function show(i){var s=document.querySelectorAll('.sheet'),t=document.querySelectorAll('.t');
for(var j=0;j<s.length;j++){s[j].classList.toggle('on',j==i);t[j].classList.toggle('on',j==i);}}"""
        return HtmlKit.page("<div class='tabs'>$tabs</div>$body", "grid", script)
    }

    private class SheetData(val rows: List<HtmlKit.Row>, val maxCols: Int, val truncated: Boolean)

    private fun parseWorkbook(b: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val p = XmlKit.parser(b)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && XmlKit.local(p.name) == "sheet") {
                val name = XmlKit.attr(p, "name") ?: "Sheet${out.size + 1}"
                val hidden = XmlKit.attr(p, "state")?.contains("hidden") == true
                var rid: String? = null
                for (i in 0 until p.attributeCount) {
                    val an = p.getAttributeName(i)
                    if (an.endsWith(":id") || an == "id") rid = p.getAttributeValue(i)
                }
                if (rid != null && !hidden) out.add(Pair(name, rid))
            }
            ev = p.next()
        }
        return out
    }

    private fun parseShared(b: ByteArray?): List<String> {
        val list = ArrayList<String>()
        if (b == null) return list
        val p = XmlKit.parser(b)
        var cur = StringBuilder()
        var rph = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (XmlKit.local(p.name)) {
                    "si" -> cur = StringBuilder()
                    "rPh" -> rph++
                    "t" -> if (rph == 0) cur.append(p.nextText())
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (XmlKit.local(p.name)) {
                    "si" -> list.add(cur.toString())
                    "rPh" -> rph--
                }
            }
            ev = p.next()
        }
        return list
    }

    private fun parseStyles(b: ByteArray?): List<Kind> {
        if (b == null) return emptyList()
        val custom = HashMap<Int, String>()
        val xfs = ArrayList<Int>()
        var inXfs = false
        val p = XmlKit.parser(b)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (XmlKit.local(p.name)) {
                    "numFmt" -> {
                        val id = XmlKit.attr(p, "numFmtId")?.toIntOrNull()
                        val code = XmlKit.attr(p, "formatCode")
                        if (id != null && code != null) custom[id] = code
                    }
                    "cellXfs" -> inXfs = true
                    "xf" -> if (inXfs) xfs.add(XmlKit.attr(p, "numFmtId")?.toIntOrNull() ?: 0)
                }
            } else if (ev == XmlPullParser.END_TAG && XmlKit.local(p.name) == "cellXfs") {
                inXfs = false
            }
            ev = p.next()
        }
        return xfs.map { kindOf(it, custom[it]) }
    }

    private fun kindOf(id: Int, code: String?): Kind {
        when (id) {
            9, 10 -> return Kind.PERCENT
            in 14..17, in 27..31, in 34..36, in 50..58 -> return Kind.DATE
            in 18..21, in 45..47 -> return Kind.TIME
            22 -> return Kind.DATETIME
        }
        if (code == null) return Kind.GENERAL
        val c = code.lowercase()
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
        val hasDate = c.contains('d') || c.contains('y')
        val hasTime = c.contains('h') || c.contains('s')
        return when {
            hasDate && hasTime -> Kind.DATETIME
            hasDate -> Kind.DATE
            hasTime -> Kind.TIME
            c.contains('%') -> Kind.PERCENT
            else -> Kind.GENERAL
        }
    }

    private fun plain(d: Double): String =
        if (d == Math.rint(d) && abs(d) < 1e15) d.toLong().toString()
        else BigDecimal(d).round(MathContext(12)).stripTrailingZeros().toPlainString()

    private fun formatNumber(v: String, kind: Kind): String {
        val d = v.toDoubleOrNull() ?: return v
        return try {
            when (kind) {
                Kind.DATE -> if (d in 0.0..2958465.0) excelEpoch.plusDays(floor(d).toLong()).format(dateFmt) else plain(d)
                Kind.DATETIME -> if (d in 0.0..2958465.0) excelEpoch.plusSeconds((d * 86400).roundToLong()).format(dateTimeFmt) else plain(d)
                Kind.TIME -> {
                    val secs = ((d - floor(d)) * 86400).roundToLong()
                    String.format("%02d:%02d", secs / 3600, secs % 3600 / 60)
                }
                Kind.PERCENT -> plain(d * 100) + "%"
                Kind.GENERAL -> plain(d)
            }
        } catch (_: Exception) {
            v
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun parseSheet(b: ByteArray, shared: List<String>, styles: List<Kind>): SheetData {
        val rows = ArrayList<HtmlKit.Row>()
        var cells = ArrayList<String>()
        var rowNum = 0
        var col = 0
        var type = ""
        var style = 0
        var value: String? = null
        val inline = StringBuilder()
        var inCell = false
        var maxCols = 0
        var truncated = false

        val p = XmlKit.parser(b)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (XmlKit.local(p.name)) {
                    "row" -> {
                        rowNum = XmlKit.attr(p, "r")?.toIntOrNull() ?: (rowNum + 1)
                        cells = ArrayList()
                    }
                    "c" -> {
                        inCell = true
                        col = XmlKit.attr(p, "r")?.let { colIndex(it) }?.takeIf { it >= 0 } ?: cells.size
                        type = XmlKit.attr(p, "t") ?: ""
                        style = XmlKit.attr(p, "s")?.toIntOrNull() ?: 0
                        value = null
                        inline.setLength(0)
                    }
                    "v" -> if (inCell) value = p.nextText()
                    "t" -> if (inCell) inline.append(p.nextText())
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (XmlKit.local(p.name)) {
                    "c" -> {
                        inCell = false
                        if (col < MAX_COLS) {
                            val v = value
                            val text = when (type) {
                                "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "inlineStr" -> inline.toString()
                                "b" -> if (v == "1") "TRUE" else "FALSE"
                                "str", "e" -> v ?: ""
                                else -> if (v == null) "" else formatNumber(v, styles.getOrElse(style) { Kind.GENERAL })
                            }
                            if (text.isNotEmpty()) {
                                while (cells.size < col) cells.add("")
                                if (col < cells.size) cells[col] = text else cells.add(text)
                            }
                        }
                    }
                    "row" -> if (cells.isNotEmpty()) {
                        if (rows.size >= MAX_ROWS) {
                            truncated = true
                            break
                        }
                        rows.add(HtmlKit.Row(rowNum, cells))
                        if (cells.size > maxCols) maxCols = cells.size
                    }
                }
            }
            ev = p.next()
        }
        return SheetData(rows, maxCols, truncated)
    }
}

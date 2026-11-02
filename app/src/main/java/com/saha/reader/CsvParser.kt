package com.saha.reader

object CsvParser {
    private const val MAX_ROWS = 10000
    private const val MAX_COLS = 200

    fun toHtml(text: String, fileName: String, truncatedFile: Boolean): String {
        val firstLine = text.substringBefore('\n')
        val delim = when {
            fileName.lowercase().endsWith(".tsv") -> '\t'
            firstLine.count { it == '\t' } > firstLine.count { it == ',' } -> '\t'
            firstLine.count { it == ';' } > firstLine.count { it == ',' } -> ';'
            else -> ','
        }
        val rows = ArrayList<HtmlKit.Row>()
        var cells = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var maxCols = 0
        var i = 0
        var truncated = truncatedFile

        fun endCell() {
            if (cells.size < MAX_COLS) cells.add(cur.toString())
            cur.setLength(0)
        }
        fun endRow() {
            endCell()
            if (cells.any { it.isNotEmpty() }) {
                rows.add(HtmlKit.Row(rows.size + 1, cells))
                if (cells.size > maxCols) maxCols = cells.size
            }
            cells = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else quoted = false
                } else cur.append(c)
            } else when (c) {
                '"' -> quoted = true
                delim -> endCell()
                '\r' -> {}
                '\n' -> {
                    endRow()
                    if (rows.size >= MAX_ROWS) { truncated = true; break }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || cells.isNotEmpty()) endRow()

        val body = StringBuilder("<div class='sheet on'>")
        if (truncated) body.append("<div class='note'>File is large — showing the first part only.</div>")
        if (rows.isEmpty()) body.append("<div class='msg'>This file is empty.</div>")
        else HtmlKit.grid(body, rows, maxCols)
        body.append("</div>")
        return HtmlKit.page(body.toString(), "grid")
    }
}

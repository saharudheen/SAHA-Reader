package com.saha.reader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import androidx.appcompat.widget.SearchView
import java.io.IOException
import kotlin.concurrent.thread

/** Shows Word, Excel, CSV and text files as HTML in a WebView. */
class WebDocActivity : BaseViewerActivity() {
    private lateinit var web: WebView
    private var textZoom = 100
    private var lastQuery = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        if (!initViewer()) return
        web = findViewById(R.id.webView)
        web.settings.apply {
            javaScriptEnabled = true // only for switching Excel sheet tabs; all file text is escaped
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = this@WebDocActivity.textZoom
        }
        web.setFindListener { active, total, done ->
            if (done) supportActionBar?.subtitle = when {
                lastQuery.isEmpty() -> null
                total == 0 -> "No matches"
                else -> "${active + 1} of $total"
            }
        }

        thread {
            val html = try {
                build()
            } catch (e: OutOfMemoryError) {
                HtmlKit.message("File too large", "This file is too big to open on this phone.")
            } catch (e: Exception) {
                HtmlKit.message("Can't open this file", e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hideProgress()
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    }

    // Swipe to the next file only when the page can't scroll further that way.
    override fun canSwipe(direction: Int): Boolean =
        !::web.isInitialized || !web.canScrollHorizontally(direction)

    private fun build(): String {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read this file")
        return input.use { inp ->
            when (docType) {
                DocType.WORD -> DocxParser.toHtml(inp)
                DocType.EXCEL -> XlsxParser.toHtml(inp)
                DocType.CSV -> {
                    val (bytes, cut) = IoKit.readLimited(inp, 8 * 1024 * 1024)
                    CsvParser.toHtml(IoKit.decodeText(bytes), fileName, cut)
                }
                DocType.OLD_WORD -> HtmlKit.message(
                    "Old Word format (.doc)",
                    "Saha Reader opens modern .docx files. Save this file as .docx on a PC, or use ⋮ → Open with another app."
                )
                DocType.OLD_EXCEL -> HtmlKit.message(
                    "Old Excel format (.xls)",
                    "Saha Reader opens modern .xlsx files. Save this file as .xlsx on a PC, or use ⋮ → Open with another app."
                )
                else -> textPage(inp)
            }
        }
    }

    private fun textPage(inp: java.io.InputStream): String {
        val (bytes, cut) = IoKit.readLimited(inp, 4 * 1024 * 1024)
        if (docType != DocType.TEXT && IoKit.looksBinary(bytes)) {
            return HtmlKit.message("Unsupported file", "This file type can't be shown. Try ⋮ → Open with another app.")
        }
        val note = if (cut) "<div class='note'>File is large — showing the first 4 MB only.</div>" else ""
        return HtmlKit.page("$note<pre>${HtmlKit.esc(IoKit.decodeText(bytes))}</pre>")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_web, menu)
        val sv = menu.findItem(R.id.action_search)?.actionView as? SearchView
        sv?.queryHint = "Search in file"
        sv?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (::web.isInitialized && lastQuery.isNotEmpty()) web.findNext(true)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!::web.isInitialized) return true
                lastQuery = newText ?: ""
                if (lastQuery.isEmpty()) {
                    web.clearMatches()
                    supportActionBar?.subtitle = null
                } else {
                    web.findAllAsync(lastQuery)
                }
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!::web.isInitialized) return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_zoom_in -> { textZoom = (textZoom + 20).coerceAtMost(300); web.settings.textZoom = textZoom; return true }
            R.id.action_zoom_out -> { textZoom = (textZoom - 20).coerceAtLeast(60); web.settings.textZoom = textZoom; return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}

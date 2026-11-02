package com.saha.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.min

class PdfActivity : BaseViewerActivity() {
    // PdfRenderer is not thread-safe: every renderer call happens on this single thread.
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageW = IntArray(0)
    private var pageH = IntArray(0)
    private var pageCount = 0
    private var baseWidth = 1080
    private var renderWidth = 1080
    private var quality = 1
    private var generation = 0

    @Volatile private var destroyed = false
    @Volatile private var visFirst = 0
    @Volatile private var visLast = 3

    private lateinit var recycler: ZoomRecyclerView
    private lateinit var pageLabel: TextView
    private val pending = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)
        if (!initViewer()) return
        recycler = findViewById(R.id.pdfList)
        pageLabel = findViewById(R.id.pageLabel)
        recycler.layoutManager = LinearLayoutManager(this)
        baseWidth = min(resources.displayMetrics.widthPixels, 1440)
        renderWidth = baseWidth
        pageLabel.setOnClickListener { showGoTo() }
        recycler.onZoomSettled = { z -> onZoomChanged(z) }

        exec.execute {
            try {
                val r = openRenderer()
                val n = r.pageCount
                val w = IntArray(n)
                val h = IntArray(n)
                for (i in 0 until n) {
                    val p = r.openPage(i)
                    w[i] = p.width
                    h[i] = p.height
                    p.close()
                }
                renderer = r
                ui.post { if (!destroyed) onOpened(n, w, h) }
            } catch (e: SecurityException) {
                ui.post { showError("This PDF is password protected.\nUse ⋮ → Open with another app.") }
            } catch (e: Exception) {
                ui.post { showError("Could not open this PDF.\n${e.message ?: ""}") }
            }
        }
    }

    override fun canSwipe(direction: Int): Boolean = !::recycler.isInitialized || !recycler.isZoomed

    /** Opens the renderer directly; if the source isn't seekable, copies it to cache first. */
    private fun openRenderer(): PdfRenderer {
        try {
            val fd = contentResolver.openFileDescriptor(uri, "r")
            if (fd != null) {
                try {
                    val r = PdfRenderer(fd)
                    pfd = fd
                    return r
                } catch (e: IOException) {
                    fd.close()
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
        }
        val f = File(cacheDir, "current.pdf")
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read file")
        input.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        return PdfRenderer(fd)
    }

    private fun onOpened(n: Int, w: IntArray, h: IntArray) {
        pageCount = n
        pageW = w
        pageH = h
        hideProgress()
        if (n == 0) {
            showError("This PDF has no pages.")
            return
        }
        supportActionBar?.subtitle = "$n page${if (n == 1) "" else "s"} · pinch to zoom"
        recycler.adapter = PageAdapter()
        pageLabel.visibility = View.VISIBLE
        updateLabel()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateLabel()
        })
    }

    /** Re-render pages at a higher resolution when zoomed in, so text stays sharp. */
    private fun onZoomChanged(z: Float) {
        val q = when {
            z > 2.2f -> 3
            z > 1.3f -> 2
            else -> 1
        }
        if (q == quality || pageCount == 0) return
        quality = q
        renderWidth = min(baseWidth * q, 2400)
        generation++
        pending.clear()
        cache.evictAll()
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun updateLabel() {
        val lm = recycler.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first >= 0) {
            visFirst = first
            visLast = last
        }
        val full = lm.findFirstCompletelyVisibleItemPosition()
        val shown = (if (full >= 0) full else first).coerceAtLeast(0)
        pageLabel.text = "${shown + 1} / $pageCount"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderPage(i: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        val page = r.openPage(i)
        try {
            val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private fun requestRender(pos: Int) {
        if (!pending.add(pos)) return
        val gen = generation
        val width = renderWidth
        exec.execute {
            val far = pos < visFirst - 2 || pos > visLast + 2
            val bmp = if (destroyed || far || gen != generation) null
            else try { renderPage(pos, width) } catch (_: Throwable) { null }
            ui.post {
                if (gen != generation) return@post
                pending.remove(pos)
                if (bmp != null && !destroyed) {
                    cache.put(pos, bmp)
                    (recycler.findViewHolderForAdapterPosition(pos) as? PageHolder)?.image?.setImageBitmap(bmp)
                }
            }
        }
    }

    class PageHolder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.pageImage)
        var boundPos = -1
    }

    inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(layoutInflater.inflate(R.layout.item_pdf_page, parent, false))

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val avail = (if (recycler.width > 0) recycler.width else resources.displayMetrics.widthPixels) - dp(16)
            val lp = holder.image.layoutParams
            lp.height = (avail.toFloat() * pageH[position] / pageW[position]).toInt()
            holder.image.layoutParams = lp
            val bmp = cache.get(position)
            if (bmp != null) {
                holder.image.setImageBitmap(bmp)
            } else {
                // Keep the old (lower-res) picture while the sharper one renders.
                if (holder.boundPos != position) holder.image.setImageDrawable(null)
                requestRender(position)
            }
            holder.boundPos = position
        }
    }

    private fun showGoTo() {
        if (pageCount == 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – $pageCount"
        }
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val p = (n - 1).coerceIn(0, pageCount - 1)
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::recycler.isInitialized) recycler.post {
            recycler.resetZoom()
            recycler.adapter?.notifyDataSetChanged()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_goto) {
            showGoTo()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        destroyed = true
        exec.execute {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        exec.shutdown()
        cache.evictAll()
        super.onDestroy()
    }
}

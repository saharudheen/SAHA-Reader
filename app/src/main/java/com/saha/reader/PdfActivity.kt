package com.saha.reader

import android.app.Dialog
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
import android.widget.Toast
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
    private var renderWidth = 1080

    @Volatile private var destroyed = false
    @Volatile private var visFirst = 0
    @Volatile private var visLast = 3

    private lateinit var recycler: RecyclerView
    private lateinit var pageLabel: TextView
    private val pending = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)
        if (!initViewer()) return
        recycler = findViewById(R.id.pdfList)
        pageLabel = findViewById(R.id.pageLabel)
        recycler.layoutManager = LinearLayoutManager(this)
        renderWidth = min(resources.displayMetrics.widthPixels, 1600)
        pageLabel.setOnClickListener { showGoTo() }

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
        supportActionBar?.subtitle = "$n page${if (n == 1) "" else "s"}"
        recycler.adapter = PageAdapter()
        pageLabel.visibility = View.VISIBLE
        updateLabel()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateLabel()
        })
    }

    private fun updateLabel() {
        val lm = recycler.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first >= 0) {
            visFirst = first
            visLast = last
        }
        val shown = (if (lm.findFirstCompletelyVisibleItemPosition() >= 0) lm.findFirstCompletelyVisibleItemPosition() else first).coerceAtLeast(0)
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
        exec.execute {
            val far = pos < visFirst - 2 || pos > visLast + 2
            val bmp = if (destroyed || far) null else try { renderPage(pos, renderWidth) } catch (_: Throwable) { null }
            ui.post {
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
                holder.image.setImageDrawable(null)
                requestRender(position)
            }
            holder.itemView.setOnClickListener { openZoom(holder.bindingAdapterPosition) }
        }
    }

    /** Full-screen pinch-zoom view of one page, rendered at higher resolution. */
    private fun openZoom(pos: Int) {
        if (pos < 0) return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val zv = ZoomImageView(this)
        dialog.setContentView(zv)
        dialog.show()
        Toast.makeText(this, "Page ${pos + 1} · pinch or double-tap to zoom", Toast.LENGTH_SHORT).show()
        val w = min((renderWidth * 2.2f).toInt(), 2600)
        exec.execute {
            val b = if (destroyed) null else try { renderPage(pos, w) } catch (_: Throwable) { null }
            ui.post { if (b != null && dialog.isShowing) zv.setImageBitmap(b) }
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
        if (::recycler.isInitialized) recycler.post { recycler.adapter?.notifyDataSetChanged() }
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

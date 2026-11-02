package com.saha.reader

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

abstract class BaseViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_TYPE = "type"
    }

    protected lateinit var uri: Uri
    protected var fileName = ""
    protected var docType = DocType.UNKNOWN
    private var swipe: GestureDetector? = null

    /** Call right after setContentView. Returns false if there is nothing to show. */
    protected fun initViewer(): Boolean {
        val u = intent?.data
        if (u == null) {
            finish()
            return false
        }
        uri = u
        fileName = intent.getStringExtra(EXTRA_NAME) ?: FileRouter.displayName(this, u)
        docType = intent.getStringExtra(EXTRA_TYPE)?.let {
            try { DocType.valueOf(it) } catch (_: Exception) { null }
        } ?: FileRouter.detect(fileName, mime())
        title = fileName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        rememberRecent()
        setupSwipe()
        return true
    }

    // ---------- swipe between files ----------

    /** Viewers return false when the gesture should pan/zoom the content instead. */
    protected open fun canSwipe(direction: Int): Boolean = true

    private fun setupSwipe() {
        val index = Playlist.indexOf(uri)
        if (index < 0 || Playlist.size() < 2) return
        if (hintShown.add(uri)) {
            Toast.makeText(this, "File ${index + 1} of ${Playlist.size()} · swipe ← → for other files", Toast.LENGTH_SHORT).show()
        }
        val minDist = 90 * resources.displayMetrics.density
        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e1.pointerCount > 1 || e2.pointerCount > 1) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < minDist || abs(dx) < abs(dy) * 2 || abs(velocityX) < 600) return false
                val dir = if (dx < 0) 1 else -1 // finger moves left -> next file
                if (!canSwipe(dir)) return false
                goTo(dir)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) {
            // ignore pinches for swiping
        } else {
            swipe?.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun goTo(dir: Int) {
        val i = Playlist.indexOf(uri)
        if (i < 0) return
        val next = Playlist.get(i + dir)
        if (next == null) {
            Toast.makeText(this, if (dir > 0) "This is the last file" else "This is the first file", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            FileRouter.open(this, next.uri, next.name)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open ${next.name}", Toast.LENGTH_SHORT).show()
            return
        }
        finish()
        @Suppress("DEPRECATION")
        if (dir > 0) overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        else overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    // ---------- recent files ----------

    private fun rememberRecent() {
        val persisted = try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Exception) {
            contentResolver.persistedUriPermissions.any { p -> p.isReadPermission && (p.uri == uri || treeCovers(p.uri)) }
        }
        if (persisted) {
            Recents.add(this, Recent(uri.toString(), fileName, docType.name, System.currentTimeMillis()))
        }
    }

    /** True when [tree] is a folder the user granted and this file sits inside it. */
    private fun treeCovers(tree: Uri): Boolean = try {
        DocumentsContract.isTreeUri(tree) && tree.authority == uri.authority &&
            DocumentsContract.getDocumentId(uri).startsWith(DocumentsContract.getTreeDocumentId(tree))
    } catch (_: Exception) {
        false
    }

    protected fun mime(): String =
        (try { contentResolver.getType(uri) } catch (_: Exception) { null }) ?: docType.mime

    protected fun showError(msg: String) {
        findViewById<View>(R.id.progress)?.visibility = View.GONE
        findViewById<TextView>(R.id.errorText)?.apply {
            text = msg
            visibility = View.VISIBLE
        }
    }

    protected fun hideProgress() {
        findViewById<View>(R.id.progress)?.visibility = View.GONE
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_common, menu)
        val inList = ::uri.isInitialized && Playlist.indexOf(uri) >= 0 && Playlist.size() > 1
        menu.findItem(R.id.action_prev)?.isVisible = inList
        menu.findItem(R.id.action_next)?.isVisible = inList
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_share -> { share(); true }
        R.id.action_open_with -> { openWith(); true }
        R.id.action_next -> { goTo(1); true }
        R.id.action_prev -> { goTo(-1); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime())
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Share $fileName"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWith() {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No other app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Files whose "File x of y" hint was already shown this session. */
private val hintShown = HashSet<Uri>()

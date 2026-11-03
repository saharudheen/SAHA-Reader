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
import androidx.core.content.FileProvider
import java.io.File
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
    private var playIndex = -1

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

    /** Uses the current list (recent files / same folder) or builds one from this file's folder. */
    private fun setupPlaylist() {
        playIndex = Playlist.indexOf(uri)
        if (playIndex >= 0 || !FolderFiles.hasAccess(this)) return
        val file = FolderFiles.fileFor(this, uri, fileName) ?: return
        val dir = file.parentFile ?: return
        val files = FolderFiles.list(dir)
        val idx = files.indexOfFirst { it.absolutePath == file.absolutePath }
        if (idx < 0 || files.size < 2) return
        Playlist.set(files.map { Playlist.Item(Uri.fromFile(it), it.name) }, dir.name)
        playIndex = idx
    }

    private fun setupSwipe() {
        setupPlaylist()
        val index = playIndex
        if (index < 0 || Playlist.size() < 2) return
        if (!Playlist.hintShown) {
            Playlist.hintShown = true
            Toast.makeText(this, "${index + 1} of ${Playlist.size()} in ${Playlist.label} · swipe ← → for other files", Toast.LENGTH_SHORT).show()
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
        val i = playIndex
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
        val persisted = if (uri.scheme == "file") true else try {
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
        val inList = playIndex >= 0 && Playlist.size() > 1
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

    /** file:// links can't be given to other apps, so wrap them in a content:// link. */
    private fun shareableUri(): Uri {
        if (uri.scheme != "file") return uri
        return try {
            FileProvider.getUriForFile(this, "$packageName.files", File(uri.path ?: ""))
        } catch (_: Exception) {
            uri
        }
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime())
            .putExtra(Intent.EXTRA_STREAM, shareableUri())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Share $fileName"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWith() {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareableUri(), mime())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No other app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}


package com.saha.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/** Lists the readable files in a folder the user picked; opening one lets them swipe through the rest. */
class FolderActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE = "tree"
        const val EXTRA_DOC_ID = "docId"
        const val EXTRA_TITLE = "title"
    }

    private class Entry(val uri: Uri, val docId: String, val name: String, val type: DocType?, val modified: Long, val size: Long) {
        val isDir get() = type == null
    }

    private val entries = ArrayList<Entry>()
    private lateinit var tree: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val treeStr = intent.getStringExtra(EXTRA_TREE)
        if (treeStr == null) {
            finish()
            return
        }
        tree = Uri.parse(treeStr)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Folder"
        val list = findViewById<RecyclerView>(R.id.folderList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()

        thread {
            val result = try { load() } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<View>(R.id.progress).visibility = View.GONE
                val err = findViewById<TextView>(R.id.errorText)
                when {
                    result == null -> { err.text = "Can't read this folder. Please choose it again from the home screen."; err.visibility = View.VISIBLE }
                    result.isEmpty() -> { err.text = "No PDF, Word, Excel, text or image files here."; err.visibility = View.VISIBLE }
                    else -> {
                        entries.addAll(result)
                        val files = result.count { !it.isDir }
                        supportActionBar?.subtitle = "$files file${if (files == 1) "" else "s"}"
                        list.adapter?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun load(): List<Entry> {
        val docId = intent.getStringExtra(EXTRA_DOC_ID) ?: DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<Entry>()
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2)
                val modified = if (c.isNull(3)) 0L else c.getLong(3)
                val size = if (c.isNull(4)) 0L else c.getLong(4)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    out.add(Entry(docUri, id, name, null, modified, 0))
                } else {
                    val t = FileRouter.detect(name, mime)
                    if (t != DocType.UNKNOWN) out.add(Entry(docUri, id, name, t, modified, size))
                }
            }
        }
        out.sortWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    private fun open(e: Entry) {
        if (e.isDir) {
            startActivity(
                Intent(this, FolderActivity::class.java)
                    .putExtra(EXTRA_TREE, tree.toString())
                    .putExtra(EXTRA_DOC_ID, e.docId)
                    .putExtra(EXTRA_TITLE, e.name)
            )
            return
        }
        Playlist.set(entries.filter { !it.isDir }.map { Playlist.Item(it.uri, it.name) })
        try {
            FileRouter.open(this, e.uri, e.name)
        } catch (ex: Exception) {
            Toast.makeText(this, "Cannot open ${e.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class Adapter : RecyclerView.Adapter<MainActivity.RecentHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MainActivity.RecentHolder(layoutInflater.inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: MainActivity.RecentHolder, position: Int) {
            val e = entries[position]
            val t = e.type
            if (t == null) {
                holder.badge.text = "DIR"
                holder.badge.backgroundTintList = ColorStateList.valueOf(0xFFF2994A.toInt())
                holder.sub.text = "Folder"
            } else {
                holder.badge.text = t.badge
                holder.badge.backgroundTintList = ColorStateList.valueOf(t.color)
                val parts = ArrayList<String>()
                parts.add(t.label)
                if (e.size > 0) parts.add(Formatter.formatShortFileSize(this@FolderActivity, e.size))
                if (e.modified > 0) parts.add(DateUtils.getRelativeTimeSpanString(e.modified).toString())
                holder.sub.text = parts.joinToString(" · ")
            }
            holder.name.text = e.name
            holder.itemView.setOnClickListener { open(e) }
            holder.itemView.setOnLongClickListener(null)
        }
    }
}

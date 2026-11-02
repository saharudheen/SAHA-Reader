package com.saha.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private val items = ArrayList<Recent>()
    private lateinit var adapter: RecentAdapter
    private lateinit var emptyText: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            FileRouter.open(this, uri)
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            try {
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            val name = folderName(tree)
            getSharedPreferences("saha_reader", MODE_PRIVATE).edit()
                .putString("last_tree", tree.toString()).putString("last_tree_name", name).apply()
            openFolder(tree, name)
        }
    }

    private fun openFolder(tree: Uri, name: String) {
        startActivity(
            Intent(this, FolderActivity::class.java)
                .putExtra(FolderActivity.EXTRA_TREE, tree.toString())
                .putExtra(FolderActivity.EXTRA_TITLE, name)
        )
    }

    private fun folderName(tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        contentResolver.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Folder"
    } catch (_: Exception) {
        "Folder"
    }

    private fun refreshLastFolder() {
        val btn = findViewById<TextView>(R.id.btnLastFolder)
        val prefs = getSharedPreferences("saha_reader", MODE_PRIVATE)
        val tree = prefs.getString("last_tree", null)?.let { Uri.parse(it) }
        val stillAllowed = tree != null && contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (tree == null || !stillAllowed) {
            btn.visibility = View.GONE
            return
        }
        val name = prefs.getString("last_tree_name", null) ?: "Folder"
        btn.text = "Continue in “$name” folder  →"
        btn.visibility = View.VISIBLE
        btn.setOnClickListener { openFolder(tree, name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        emptyText = findViewById(R.id.emptyText)

        findViewById<View>(R.id.btnOpen).setOnClickListener { picker.launch(MimeSets.ALL) }
        findViewById<View>(R.id.btnFolder).setOnClickListener { folderPicker.launch(null) }
        findViewById<View>(R.id.btnPdf).setOnClickListener { picker.launch(MimeSets.PDF) }
        findViewById<View>(R.id.btnWord).setOnClickListener { picker.launch(MimeSets.WORD) }
        findViewById<View>(R.id.btnExcel).setOnClickListener { picker.launch(MimeSets.EXCEL) }
        findViewById<View>(R.id.btnText).setOnClickListener { picker.launch(MimeSets.TEXT) }
        findViewById<View>(R.id.btnImage).setOnClickListener { picker.launch(MimeSets.IMAGE) }

        val list = findViewById<RecyclerView>(R.id.recentList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = RecentAdapter()
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshLastFolder()
    }

    private fun refresh() {
        items.clear()
        items.addAll(Recents.load(this))
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear) {
            AlertDialog.Builder(this)
                .setMessage("Clear the recent files list? (Files are not deleted.)")
                .setPositiveButton("Clear") { _, _ -> Recents.clear(this); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class RecentHolder(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.badge)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    inner class RecentAdapter : RecyclerView.Adapter<RecentHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentHolder =
            RecentHolder(layoutInflater.inflate(R.layout.item_recent, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecentHolder, position: Int) {
            val r = items[position]
            val t = r.docType
            holder.badge.text = t.badge
            holder.badge.backgroundTintList = ColorStateList.valueOf(t.color)
            holder.name.text = r.name
            holder.sub.text = "${t.label} · ${DateUtils.getRelativeTimeSpanString(r.time)}"
            holder.itemView.setOnClickListener {
                try {
                    Playlist.set(items.map { Playlist.Item(Uri.parse(it.uri), it.name) })
                    FileRouter.open(this@MainActivity, Uri.parse(r.uri), r.name)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "File is no longer available", Toast.LENGTH_SHORT).show()
                    Recents.remove(this@MainActivity, r.uri)
                    refresh()
                }
            }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(r.name)
                    .setMessage("Remove from recent files?")
                    .setPositiveButton("Remove") { _, _ -> Recents.remove(this@MainActivity, r.uri); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
    }
}

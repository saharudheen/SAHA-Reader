package com.saha.reader

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (!granted) Toast.makeText(this, "Permission not given — swiping works only in Recent files", Toast.LENGTH_LONG).show()
        refreshAccessBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        emptyText = findViewById(R.id.emptyText)

        findViewById<View>(R.id.btnOpen).setOnClickListener { picker.launch(MimeSets.ALL) }
        findViewById<View>(R.id.btnPdf).setOnClickListener { picker.launch(MimeSets.PDF) }
        findViewById<View>(R.id.btnWord).setOnClickListener { picker.launch(MimeSets.WORD) }
        findViewById<View>(R.id.btnExcel).setOnClickListener { picker.launch(MimeSets.EXCEL) }
        findViewById<View>(R.id.btnText).setOnClickListener { picker.launch(MimeSets.TEXT) }
        findViewById<View>(R.id.btnImage).setOnClickListener { picker.launch(MimeSets.IMAGE) }
        findViewById<View>(R.id.btnAllowSwipe).setOnClickListener { askAccess() }

        val list = findViewById<RecyclerView>(R.id.recentList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = RecentAdapter()
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshAccessBanner()
    }

    private fun refresh() {
        items.clear()
        items.addAll(Recents.load(this))
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Shows the "allow swiping" link until file access is given. */
    private fun refreshAccessBanner() {
        findViewById<View>(R.id.btnAllowSwipe).visibility =
            if (FolderFiles.hasAccess(this)) View.GONE else View.VISIBLE
    }

    private fun askAccess() {
        AlertDialog.Builder(this)
            .setTitle("Swipe between files")
            .setMessage(
                "To let you swipe to the next / previous file in the same folder, Saha Reader needs permission to read your files.\n\n" +
                    "Files are only read on this phone — the app has no internet access." +
                    (if (Build.VERSION.SDK_INT >= 30) "\n\nOn the next screen, turn on “Allow access to manage all files”." else "")
            )
            .setPositiveButton("Allow") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton("Not now", null)
            .show()
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
                    Playlist.set(items.map { Playlist.Item(Uri.parse(it.uri), it.name) }, "Recent files")
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

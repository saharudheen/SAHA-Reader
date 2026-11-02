package com.saha.reader

import android.content.Intent
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

abstract class BaseViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_TYPE = "type"
    }

    protected lateinit var uri: Uri
    protected var fileName = ""
    protected var docType = DocType.UNKNOWN

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
        return true
    }

    private fun rememberRecent() {
        val persisted = try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Exception) {
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
        if (persisted) {
            Recents.add(this, Recent(uri.toString(), fileName, docType.name, System.currentTimeMillis()))
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_common, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_share -> { share(); true }
        R.id.action_open_with -> { openWith(); true }
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

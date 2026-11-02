package com.saha.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Invisible entry point for "Open with" and "Share to" from other apps. */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> streamExtra(intent)
            else -> intent?.data
        }
        if (uri == null) {
            Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show()
        } else {
            try {
                FileRouter.open(this, uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
}

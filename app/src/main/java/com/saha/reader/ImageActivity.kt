package com.saha.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import kotlin.concurrent.thread
import kotlin.math.max

class ImageActivity : BaseViewerActivity() {
    private lateinit var zoom: ZoomImageView
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)
        if (!initViewer()) return
        zoom = findViewById(R.id.zoomImage)

        thread {
            var dims = ""
            val b: Bitmap? = try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                dims = "${opts.outWidth} × ${opts.outHeight}"
                var sample = 1
                while (max(opts.outWidth, opts.outHeight) / sample > 4096) sample *= 2
                val decoded = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                decoded?.let { applyExif(it) }
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hideProgress()
                if (b == null) {
                    showError("Could not open this image.")
                } else {
                    bitmap = b
                    zoom.setImageBitmap(b)
                    supportActionBar?.subtitle = dims
                }
            }
        }
    }

    private fun applyExif(src: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (deg == 0f) src else rotate(src, deg)
    }

    private fun rotate(src: Bitmap, deg: Float): Bitmap {
        val mx = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, mx, true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_image, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_rotate) {
            bitmap?.let {
                try {
                    val r = rotate(it, 90f)
                    bitmap = r
                    zoom.setImageBitmap(r)
                } catch (_: OutOfMemoryError) {
                }
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

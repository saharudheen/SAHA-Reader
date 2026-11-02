package com.saha.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** ImageView with pinch-zoom, pan and double-tap zoom. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val m = Matrix()
    private var curScale = 1f
    private val maxScale = 8f
    val isZoomed: Boolean get() = curScale > 1.01f

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (curScale * detector.scaleFactor).coerceIn(1f, maxScale)
            val f = newScale / curScale
            curScale = newScale
            m.postScale(f, f, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = m
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            m.postTranslate(-distanceX, -distanceY)
            fixTranslation()
            imageMatrix = m
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (curScale > 1.5f) {
                resetZoom()
            } else {
                val f = 2.5f / curScale
                curScale = 2.5f
                m.postScale(f, f, e.x, e.y)
                fixTranslation()
                imageMatrix = m
            }
            return true
        }
    })

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        val s = min(width / dw, height / dh)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        curScale = 1f
        imageMatrix = m
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (curScale > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}

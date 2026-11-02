package com.saha.reader

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView whose whole content can be pinch-zoomed and panned (used for PDF pages).
 * Unzoomed it scrolls like a normal list; zoomed, one-finger drags pan and scroll.
 */
class ZoomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var zoom = 1f
        private set
    val isZoomed: Boolean get() = zoom > 1.01f
    var onZoomSettled: ((Float) -> Unit)? = null

    private val maxZoom = 5f
    private var tx = 0f
    private var ty = 0f
    private var scrollRemainder = 0f
    private var superCancelled = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (zoom < 1.05f) resetZoom()
            onZoomSettled?.invoke(zoom)
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isZoomed || scaleDetector.isInProgress) return false
            pan(distanceX, distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!isZoomed) return false
            fling(0, (-velocityY / zoom).toInt())
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) resetZoom() else zoomBy(2.5f, e.x, e.y)
            onZoomSettled?.invoke(zoom)
            return true
        }
    })

    private fun clamp() {
        tx = tx.coerceIn(width - width * zoom, 0f)
        ty = ty.coerceIn(height - height * zoom, 0f)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val nz = (zoom * factor).coerceIn(1f, maxZoom)
        val rf = nz / zoom
        zoom = nz
        tx = fx - (fx - tx) * rf
        ty = fy - (fy - ty) * rf
        clamp()
        invalidate()
    }

    fun resetZoom() {
        zoom = 1f
        tx = 0f
        ty = 0f
        invalidate()
    }

    private fun pan(dx: Float, dy: Float) {
        tx -= dx
        val wantedTy = ty - dy
        val newTy = wantedTy.coerceIn(height - height * zoom, 0f)
        val leftover = wantedTy - newTy // screen px the zoom window couldn't absorb
        ty = newTy
        clamp()
        scrollRemainder += -leftover / zoom
        val n = scrollRemainder.toInt()
        scrollRemainder -= n
        if (n != 0) scrollBy(0, n)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(zoom, zoom)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) superCancelled = false
        scaleDetector.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        val ours = isZoomed || scaleDetector.isInProgress || ev.pointerCount > 1
        if (ours || superCancelled) {
            if (!superCancelled) {
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
                superCancelled = true
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }
}

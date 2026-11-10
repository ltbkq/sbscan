package org.sbscan.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.hypot

/** 透视矫正：把四边形区域拉正为矩形图 */
object Perspective {

    fun warp(src: Bitmap, corners: Array<PointF>): Bitmap {
        val w = ((dist(corners[0], corners[1]) + dist(corners[2], corners[3])) / 2).toInt().coerceAtLeast(64)
        val h = ((dist(corners[0], corners[3]) + dist(corners[1], corners[2])) / 2).toInt().coerceAtLeast(64)

        val m = Matrix()
        val srcPts = floatArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y
        )
        val dstPts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return src

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, m, paint)
        return out
    }

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}

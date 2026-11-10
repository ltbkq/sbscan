package org.sbscan.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 文档区域覆盖层:
 *  - 展示检测到的四边形(自动模式)
 *  - 支持四角拖拽微调(交互模式)
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 四角点, 视图坐标系, 顺序 tl,tr,br,bl */
    var quad: Array<PointF>? = null
        set(value) { field = value; invalidate() }

    var interactive: Boolean = false

    var onQuadChanged: ((Array<PointF>) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleRadius = 14f * density
    private val touchRadius = 44f * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FF6D00")
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        strokeWidth = 2f * density
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private var dragIndex = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        if (q.size != 4) return

        // 外围暗色遮罩 (整屏挖去四边形)
        val path = Path().apply {
            addRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), Path.Direction.CW)
            val inner = Path().apply {
                moveTo(q[0].x, q[0].y)
                for (i in 1..3) lineTo(q[i].x, q[i].y)
                close()
            }
            addPath(inner)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(path, dimPaint)

        // 边线 + 内部高亮
        val inner = Path().apply {
            moveTo(q[0].x, q[0].y)
            for (i in 1..3) lineTo(q[i].x, q[i].y)
            close()
        }
        canvas.drawPath(inner, fillPaint)
        canvas.drawPath(inner, linePaint)

        // 角点手柄
        for (p in q) {
            canvas.drawCircle(p.x, p.y, handleRadius, handlePaint)
            canvas.drawCircle(p.x, p.y, handleRadius, handleBorderPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        val q = quad ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragIndex = hitTest(event.x, event.y, q)
                return dragIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex >= 0) {
                    q[dragIndex].set(
                        event.x.coerceIn(0f, width.toFloat()),
                        event.y.coerceIn(0f, height.toFloat())
                    )
                    invalidate()
                    onQuadChanged?.invoke(q)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragIndex = -1
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float, q: Array<PointF>): Int {
        var best = -1
        var bestDist = touchRadius
        for (i in q.indices) {
            val d = hypot(q[i].x - x, q[i].y - y)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }
}

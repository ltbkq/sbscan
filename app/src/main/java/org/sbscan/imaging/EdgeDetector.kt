package org.sbscan.imaging

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 文档边缘检测：找出图像中纸张的四个角点。
 *
 * 流程: 缩放 -> 灰度 -> 模糊 -> Sobel 边缘 -> Otsu 二值化 -> 连通域
 *      -> 凸包 -> 最小外接矩形 -> 四角点
 */
object EdgeDetector {

    /** 检测文档四角（tl, tr, br, bl），失败返回 null */
    fun detectQuad(src: Bitmap): Array<PointF>? {
        val workW = 480
        val workH = (src.height * workW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, workW, workH, true)
        val gray = FloatArray(workW * workH)
        val px = IntArray(workW * workH)
        small.getPixels(px, 0, workW, 0, 0, workW, workH)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        small.recycle()

        val blurred = boxBlur(gray, workW, workH, 2)
        val (mag, _) = sobel(blurred, workW, workH)
        val mask = otsuMask(mag)
        dilate(mask, workW, workH, 2)

        val comp = largestComponent(mask, workW, workH) ?: return null
        val hull = convexHull(comp)
        if (hull.size < 3) return null
        val rect = minAreaRect(hull) ?: return null
        val quad = orderCorners(rect)

        // 有效性检验: 面积占比合理
        val imgArea = workW.toFloat() * workH
        val quadArea = polygonArea(quad)
        if (quadArea < imgArea * 0.12f || quadArea > imgArea * 0.995f) return null

        // 映射回原图坐标
        val sx = src.width.toFloat() / workW
        val sy = src.height.toFloat() / workH
        return Array(4) { PointF(quad[it].x * sx, quad[it].y * sy) }
    }

    // ---------------- 各步骤实现 ----------------

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = src
        repeat(2) {
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            // 横向
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f; var n = 0
                    for (k in -radius..radius) {
                        val xx = x + k
                        if (xx in 0 until w) { sum += cur[y * w + xx]; n++ }
                    }
                    tmp[y * w + x] = sum / n
                }
            }
            // 纵向
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f; var n = 0
                    for (k in -radius..radius) {
                        val yy = y + k
                        if (yy in 0 until h) { sum += tmp[yy * w + x]; n++ }
                    }
                    out[y * w + x] = sum / n
                }
            }
            cur = out
        }
        return cur
    }

    private fun sobel(gray: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val mag = FloatArray(w * h)
        val dir = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                        gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
                val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                        gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                mag[i] = hypot(gx, gy)
                dir[i] = Math.atan2(gy.toDouble(), gx.toDouble()).toFloat()
            }
        }
        return mag to dir
    }

    private fun otsuMask(mag: FloatArray): BooleanArray {
        val bins = 256
        var maxV = 1f
        for (v in mag) if (v > maxV) maxV = v
        val hist = IntArray(bins)
        for (v in mag) hist[((v / maxV) * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
        val total = mag.size
        var sum = 0.0
        for (t in 0 until bins) sum += t.toDouble() * hist[t]
        var sumB = 0.0; var wB = 0
        var best = 0.0; var threshold = 0
        for (t in 0 until bins) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        val tVal = threshold / (bins - 1f) * maxV
        return BooleanArray(mag.size) { mag[it] >= tVal }
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, times: Int) {
        repeat(times) {
            val copy = mask.clone()
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (!copy[i] && (copy[i - 1] || copy[i + 1] || copy[i - w] || copy[i + w])) mask[i] = true
                }
            }
        }
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): ArrayList<PointF>? {
        val seen = BooleanArray(mask.size)
        var best = ArrayList<PointF>()
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val pts = ArrayList<PointF>()
            var top = 0; stack[top++] = start; seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                pts.add(PointF((i % w).toFloat(), (i / w).toFloat()))
                val x = i % w; val y = i / w
                // 4 邻域 + 对角 8 邻域，利于闭合边框
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val ni = ny * w + nx
                        if (mask[ni] && !seen[ni]) { seen[ni] = true; stack[top++] = ni }
                    }
                }
            }
            if (pts.size > best.size) best = pts
        }
        return if (best.size < 30) null else best
    }

    /** Andrew 单调链凸包 */
    private fun convexHull(pts: ArrayList<PointF>): ArrayList<PointF> {
        val p = pts.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (p.size < 3) return ArrayList(p)
        val cross = { o: PointF, a: PointF, b: PointF ->
            (a.x - o.x).toDouble() * (b.y - o.y) - (a.y - o.y).toDouble() * (b.x - o.x)
        }
        val lower = ArrayList<PointF>()
        for (q in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1)
            lower.add(q)
        }
        val upper = ArrayList<PointF>()
        for (q in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1)
            upper.add(q)
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        lower.addAll(upper)
        return lower
    }

    /** 旋转卡壳求最小外接矩形 */
    private fun minAreaRect(hull: ArrayList<PointF>): Array<PointF>? {
        var bestArea = Double.MAX_VALUE
        var best: Array<PointF>? = null
        for (i in hull.indices) {
            val p1 = hull[i]; val p2 = hull[(i + 1) % hull.size]
            val ex = (p2.x - p1.x).toDouble(); val ey = (p2.y - p1.y).toDouble()
            val len = hypot(ex, ey); if (len < 1e-6) continue
            val ux = ex / len; val uy = ey / len
            val vx = -uy; val vy = ux
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (q in hull) {
                val du = q.x * ux + q.y * uy
                val dv = q.x * vx + q.y * vy
                if (du < minU) minU = du; if (du > maxU) maxU = du
                if (dv < minV) minV = dv; if (dv > maxV) maxV = dv
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                fun pt(u: Double, v: Double) = PointF((u * ux + v * vx).toFloat(), (u * uy + v * vy).toFloat())
                best = arrayOf(pt(minU, minV), pt(maxU, minV), pt(maxU, maxV), pt(minU, maxV))
            }
        }
        return best
    }

    private fun orderCorners(pts: Array<PointF>): Array<PointF> {
        val sorted = pts.sortedBy { it.y }
        val top = sorted.subList(0, 2).sortedBy { it.x }          // tl, tr
        val bottom = sorted.subList(2, 4).sortedByDescending { it.x } // br, bl
        return arrayOf(top[0], top[1], bottom[0], bottom[1])
    }

    private fun polygonArea(pts: Array<PointF>): Float {
        var s = 0f
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }
}

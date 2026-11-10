package org.sbscan.imaging

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 文档边缘检测 —— 双通道方案:
 *
 *  方案A (主): Hough 直线变换
 *    边缘图 -> Hough 累加器 -> 主方向聚类(θ 与 θ+90°) -> 取两对对边
 *    -> 直线求交得四角。对杂乱桌面/多纸张干扰鲁棒。
 *
 *  方案B (备): 连通域 + 凸包 + 旋转卡壳最小外接矩形
 *
 *  两路结果按"边缘贴合度"评分取优。角点统一按质心极角排序, 杜绝蝴蝶形错连。
 */
object EdgeDetector {

    private const val WORK_W = 480

    /** 检测文档四角（顺时针: tl, tr, br, bl），失败返回 null */
    fun detectQuad(src: Bitmap): Array<PointF>? {
        val workH = (src.height * WORK_W.toFloat() / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, WORK_W, workH, true)
        val gray = FloatArray(WORK_W * workH)
        val px = IntArray(WORK_W * workH)
        small.getPixels(px, 0, WORK_W, 0, 0, WORK_W, workH)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        small.recycle()

        val blurred = boxBlur(gray, WORK_W, workH, 2)
        val mag = sobel(blurred, WORK_W, workH)
        val mask = otsuMask(mag)
        dilate(mask, WORK_W, workH, 1)

        val quadA = houghQuad(mask, WORK_W, workH)
        val quadB = componentQuad(mask, WORK_W, workH)

        val best = listOfNotNull(quadA, quadB)
            .mapNotNull { q -> orderCorners(q)?.let { it to edgeScore(it, mask, WORK_W, workH) } }
            .filter { (_, s) -> s > 0.30 }            // 贴合度门槛
            .maxByOrNull { (_, s) -> s }
            ?.first ?: return null

        val imgArea = WORK_W.toFloat() * workH
        val area = polygonArea(best)
        if (area < imgArea * 0.12f || area > imgArea * 0.995f) return null

        val sx = src.width.toFloat() / WORK_W
        val sy = src.height.toFloat() / workH
        return Array(4) { PointF(best[it].x * sx, best[it].y * sy) }
    }

    // ================= 方案A: Hough 直线拟合 =================

    private fun houghQuad(mask: BooleanArray, w: Int, h: Int): Array<PointF>? {
        // 1. 构建累加器
        val maxRho = hypot(w.toDouble(), h.toDouble()).toInt() + 1
        val rhoBins = maxRho * 2
        val thetaBins = 180
        val acc = IntArray(rhoBins * thetaBins)
        val cosT = FloatArray(thetaBins); val sinT = FloatArray(thetaBins)
        for (t in 0 until thetaBins) {
            val rad = Math.toRadians(t.toDouble())
            cosT[t] = cos(rad).toFloat(); sinT[t] = sin(rad).toFloat()
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) continue
            for (t in 0 until thetaBins) {
                val rho = (x * cosT[t] + y * sinT[t]).toInt() + maxRho
                if (rho in 0 until rhoBins) acc[rho * thetaBins + t]++
            }
        }

        // 2. 非极大值抑制取峰
        data class Line(val theta: Int, val rho: Int, val votes: Int)
        val peaks = ArrayList<Line>()
        for (rho in 2 until rhoBins - 2) for (t in 0 until thetaBins) {
            val v = acc[rho * thetaBins + t]
            if (v < 40) continue
            var isMax = true
            for (dr in -2..2) for (dt in -2..2) {
                val tt = (t + dt + thetaBins) % thetaBins
                if (acc[(rho + dr) * thetaBins + tt] > v) { isMax = false; break }
            }
            if (isMax) peaks.add(Line(t, rho - maxRho, v))
        }
        if (peaks.size < 4) return null
        peaks.sortByDescending { it.votes }

        // 3. 主方向: 选 θ 使 θ 与 θ+90° 两侧都有强线
        var bestTheta = -1; var bestVotes = -1
        for (t in 0 until 90) {
            val v1 = peaks.filter { angleDist(it.theta, t) <= 8 }.sumOf { it.votes }
            val v2 = peaks.filter { angleDist(it.theta, t + 90) <= 8 }.sumOf { it.votes }
            val score = min(v1, v2)
            if (score > bestVotes) { bestVotes = score; bestTheta = t }
        }
        if (bestTheta < 0) return null

        fun pickPair(dir: Int): Pair<Line, Line>? {
            val group = peaks.filter { angleDist(it.theta, dir) <= 8 }
            if (group.size < 2) return null
            // 合并相近的线, 取相距最远的两簇代表"一对对边"
            val sorted = group.sortedBy { it.rho }
            val a = sorted.first(); val b = sorted.last()
            return if (b.rho - a.rho > 30) a to b else null
        }
        val pair1 = pickPair(bestTheta) ?: return null
        val pair2 = pickPair(bestTheta + 90) ?: return null

        // 4. 四线求交
        fun intersect(l1: Line, l2: Line): PointF? {
            val t1 = Math.toRadians(l1.theta.toDouble()); val t2 = Math.toRadians(l2.theta.toDouble())
            val a1 = cos(t1); val b1 = sin(t1); val c1 = l1.rho.toDouble()
            val a2 = cos(t2); val b2 = sin(t2); val c2 = l2.rho.toDouble()
            val det = a1 * b2 - a2 * b1
            if (abs(det) < 1e-6) return null
            val x = (c1 * b2 - c2 * b1) / det
            val y = (a1 * c2 - a2 * c1) / det
            return PointF(x.toFloat(), y.toFloat())
        }
        val pts = listOf(
            intersect(pair1.first, pair2.first),
            intersect(pair1.first, pair2.second),
            intersect(pair1.second, pair2.second),
            intersect(pair1.second, pair2.first)
        )
        if (pts.any { it == null }) return null
        val quad = pts.map { it!! }.toTypedArray()
        // 角点需在画面附近
        if (quad.any { it.x < -w * 0.2f || it.x > w * 1.2f || it.y < -h * 0.2f || it.y > h * 1.2f }) return null
        return quad
    }

    private fun angleDist(a: Int, b: Int): Int {
        val d = abs(a - b) % 180
        return min(d, 180 - d)
    }

    // ================= 方案B: 连通域 + 凸包 + 最小外接矩形 =================

    private fun componentQuad(mask: BooleanArray, w: Int, h: Int): Array<PointF>? {
        val comp = largestComponent(mask, w, h) ?: return null
        val hull = convexHull(comp)
        if (hull.size < 3) return null
        return minAreaRect(hull)
    }

    /** 边缘贴合度: 四条边上的采样点落在边缘图上的比例 */
    private fun edgeScore(quad: Array<PointF>, mask: BooleanArray, w: Int, h: Int): Float {
        var hit = 0; var total = 0
        for (i in 0 until 4) {
            val a = quad[i]; val b = quad[(i + 1) % 4]
            val steps = 40
            for (s in 0..steps) {
                val x = (a.x + (b.x - a.x) * s / steps).toInt()
                val y = (a.y + (b.y - a.y) * s / steps).toInt()
                if (x in 1 until w - 1 && y in 1 until h - 1) {
                    total++
                    // 3x3 邻域内有边缘即算命中
                    outer@ for (dy in -1..1) for (dx in -1..1) {
                        if (mask[(y + dy) * w + (x + dx)]) { hit++; break@outer }
                    }
                }
            }
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    // ================= 基础图像运算 =================

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = src
        repeat(2) {
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var n = 0
                for (k in -radius..radius) { val xx = x + k; if (xx in 0 until w) { sum += cur[y * w + xx]; n++ } }
                tmp[y * w + x] = sum / n
            }
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var n = 0
                for (k in -radius..radius) { val yy = y + k; if (yy in 0 until h) { sum += tmp[yy * w + x]; n++ } }
                out[y * w + x] = sum / n
            }
            cur = out
        }
        return cur
    }

    private fun sobel(gray: FloatArray, w: Int, h: Int): FloatArray {
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] + gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
            val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
            mag[i] = hypot(gx, gy)
        }
        return mag
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
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (!copy[i] && (copy[i - 1] || copy[i + 1] || copy[i - w] || copy[i + w])) mask[i] = true
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

    /** 质心极角排序, 输出顺时针 tl,tr,br,bl —— 任何凸四边形都不会错连 */
    private fun orderCorners(pts: Array<PointF>): Array<PointF>? {
        if (pts.size != 4) return null
        val cx = pts.map { it.x }.average().toFloat()
        val cy = pts.map { it.y }.average().toFloat()
        val sorted = pts.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        // 顺时针起点选最靠左上者
        val startIdx = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val rotated = ArrayList<PointF>()
        for (i in 0 until 4) rotated.add(sorted[(startIdx + i) % 4])
        // 排除自相交(蝴蝶形): 相邻边叉积应同号
        fun cross(o: PointF, a: PointF, b: PointF) =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val c1 = cross(rotated[0], rotated[1], rotated[2])
        val c2 = cross(rotated[1], rotated[2], rotated[3])
        val c3 = cross(rotated[2], rotated[3], rotated[0])
        val c4 = cross(rotated[3], rotated[0], rotated[1])
        if (!(c1 >= 0 && c2 >= 0 && c3 >= 0 && c4 >= 0) && !(c1 <= 0 && c2 <= 0 && c3 <= 0 && c4 <= 0)) return null
        return rotated.toTypedArray()
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

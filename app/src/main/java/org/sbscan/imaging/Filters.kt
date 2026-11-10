package org.sbscan.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * 图像增强滤镜 —— 核心技术对标并超越 CamScanner:
 *  - ENHANCE:  去阴影(光照估计) + CLAHE-lite 局部对比度 + 轻锐化
 *  - GRAY:    去阴影后的灰度
 *  - BW:      自适应二值化(积分图局部均值)，抗光照不均
 *  - SHARPEN: 边缘保护锐化
 */
object Filters {

    enum class Mode { ORIGINAL, ENHANCE, GRAY, BW, SHARPEN }

    fun apply(src: Bitmap, mode: Mode): Bitmap = when (mode) {
        Mode.ORIGINAL -> src
        Mode.ENHANCE -> enhance(src)
        Mode.GRAY -> {
            val corrected = removeShadow(src)
            colorMatrix(corrected, ColorMatrix().apply { setSaturation(0f) })
        }
        Mode.BW -> adaptiveBinarize(removeShadow(src))
        Mode.SHARPEN -> sharpen(src)
    }

    // ============ 增强管线: 去阴影 -> CLAHE -> 轻微饱和 ============
    private fun enhance(src: Bitmap): Bitmap {
        val noShadow = removeShadow(src)
        val clahe = clahe(noShadow)
        val cm = ColorMatrix(
            floatArrayOf(
                1.05f, 0f, 0f, 0f, 6f,
                0f, 1.05f, 0f, 0f, 6f,
                0f, 0f, 1.05f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        return colorMatrix(clahe, cm)
    }

    /**
     * 去阴影: 低分辨率估计背景光照(灰度最大值滤波近似)，再做除法归一化。
     * 书页中缝、手部阴影、灯影都能有效消除。
     */
    fun removeShadow(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // 1. 亮度 + 下采样到 ~1/16
        val ds = 16
        val sw = max(1, w / ds); val sh = max(1, h / ds)
        val small = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[min(h - 1, y * ds) * w + min(w - 1, x * ds)]
            small[y * sw + x] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }

        // 2. 灰度形态学闭运算(先膨胀后腐蚀)近似背景: 3 次3x3最大值 + 3 次3x3最小值
        repeat(3) {
            val t = small.clone()
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = min(sh - 1, max(0, y + dy)); val xx = min(sw - 1, max(0, x + dx))
                    val v = t[yy * sw + xx]; if (v > m) m = v
                }
                small[y * sw + x] = m
            }
        }
        repeat(3) {
            val t = small.clone()
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 255f
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = min(sh - 1, max(0, y + dy)); val xx = min(sw - 1, max(0, x + dx))
                    val v = t[yy * sw + xx]; if (v < m) m = v
                }
                small[y * sw + x] = m
            }
        }

        // 3. 双线性插值放大背景 + 除法归一化
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y.toFloat() / ds
            val y0 = min(sh - 1, fy.toInt()); val y1 = min(sh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = x.toFloat() / ds
                val x0 = min(sw - 1, fx.toInt()); val x1 = min(sw - 1, x0 + 1)
                val tx = fx - x0
                val bg =
                    small[y0 * sw + x0] * (1 - tx) * (1 - ty) + small[y0 * sw + x1] * tx * (1 - ty) +
                            small[y1 * sw + x0] * (1 - tx) * ty + small[y1 * sw + x1] * tx * ty
                val c = px[y * w + x]
                val r = ((c shr 16) and 0xFF); val g = ((c shr 8) and 0xFF); val b = (c and 0xFF)
                val k = 255f / max(bg, 1f)
                val r2 = min(255, (r * k).toInt()); val g2 = min(255, (g * k).toInt()); val b2 = min(255, (b * k).toInt())
                out[y * w + x] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * CLAHE-lite: 分块直方图均衡 + 块间双线性插值，局部对比度增强。
     */
    private fun clahe(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tilesX = 8; val tilesY = 8
        val tw = max(1, w / tilesX); val th = max(1, h / tilesY)
        // 每块的映射表 (256)
        val maps = Array(tilesX * tilesY) { IntArray(256) }
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val hist = IntArray(256)
            val x0 = tx * tw; val y0 = ty * th
            val x1 = if (tx == tilesX - 1) w else x0 + tw
            val y1 = if (ty == tilesY - 1) h else y0 + th
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = px[y * w + x]
                val l = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
                hist[l]++; n++
            }
            // clip limit 防止过增强
            val clip = max(1, n / 256 * 4)
            var excess = 0
            for (i in 0 until 256) if (hist[i] > clip) { excess += hist[i] - clip; hist[i] = clip }
            val add = excess / 256
            for (i in 0 until 256) hist[i] += add
            val map = maps[ty * tilesX + tx]
            var cum = 0
            for (i in 0 until 256) { cum += hist[i]; map[i] = min(255, cum * 255 / max(1, n)) }
        }
        // 双线性插值映射
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = (y.toFloat() / th) - 0.5f
            val y0 = max(0, min(tilesY - 1, fy.toInt())); val y1 = min(tilesY - 1, y0 + 1)
            val ty = min(1f, max(0f, fy - y0))
            for (x in 0 until w) {
                val fx = (x.toFloat() / tw) - 0.5f
                val x0 = max(0, min(tilesX - 1, fx.toInt())); val x1 = min(tilesX - 1, x0 + 1)
                val tx = min(1f, max(0f, fx - x0))
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                fun map1(v: Int): Int {
                    val m00 = maps[y0 * tilesX + x0][v]; val m01 = maps[y0 * tilesX + x1][v]
                    val m10 = maps[y1 * tilesX + x0][v]; val m11 = maps[y1 * tilesX + x1][v]
                    return ((m00 * (1 - tx) + m01 * tx) * (1 - ty) + (m10 * (1 - tx) + m11 * tx) * ty).toInt().coerceIn(0, 255)
                }
                out[y * w + x] = (0xFF shl 24) or (map1(r) shl 16) or (map1(g) shl 8) or map1(b)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 自适应二值化 (积分图局部均值)，抗光照不均 */
    private fun adaptiveBinarize(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
        }
        val stride = w + 1
        val integral = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += lum[y * w + x]
                integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + rowSum
            }
        }
        val r = max(w, h) / 12
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w - 1, x + r)
                val count = (x1 - x0 + 1L) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * stride + (x1 + 1)] - integral[y0 * stride + (x1 + 1)] -
                        integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]
                out[y * w + x] = if (lum[y * w + x] * count > sum * 92 / 100) -0x1000000 else -0x1
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 3x3 卷积锐化 */
    private fun sharpen(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { out[y * w + x] = px[y * w + x]; continue }
                val i = y * w + x
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val c = px[i + dy * w + dx]
                    val k = if (dx == 0 && dy == 0) 5 else -1
                    r += ((c shr 16) and 0xFF) * k
                    g += ((c shr 8) and 0xFF) * k
                    b += (c and 0xFF) * k
                }
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun colorMatrix(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}

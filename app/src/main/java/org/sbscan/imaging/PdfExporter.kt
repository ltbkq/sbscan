package org.sbscan.imaging

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File

/**
 * PDF 导出 —— 超越 CamScanner:
 *  - A4 版式
 *  - 可搜索 PDF: 图片之上叠加透明文字层(OCR 文本)，全文可搜索/可复制
 */
object PdfExporter {

    private const val A4_WIDTH = 595f   // pt
    private const val A4_HEIGHT = 842f

    data class Page(val bitmap: Bitmap, val text: String? = null)

    fun export(pages: List<Page>, outFile: File) {
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        pages.forEachIndexed { index, page ->
            val info = PdfDocument.PageInfo.Builder(A4_WIDTH.toInt(), A4_HEIGHT.toInt(), index + 1).create()
            val pdfPage = doc.startPage(info)
            val canvas = pdfPage.canvas
            canvas.drawColor(Color.WHITE)

            val scale = minOf(A4_WIDTH / page.bitmap.width, A4_HEIGHT / page.bitmap.height)
            val w = page.bitmap.width * scale
            val h = page.bitmap.height * scale
            val left = (A4_WIDTH - w) / 2
            val top = (A4_HEIGHT - h) / 2
            canvas.drawBitmap(page.bitmap, null, RectF(left, top, left + w, top + h), paint)

            // 隐形文字层: 透明文本仍以文本对象写入 PDF, 阅读器可搜索/复制/选中
            val text = page.text?.trim()
            if (!text.isNullOrEmpty()) {
                val tp = TextPaint().apply {
                    typeface = Typeface.SANS_SERIF
                    color = Color.TRANSPARENT   // 不可见, 但保留文本层
                    textSize = 10f
                }
                var ty = top + tp.textSize + 4f
                for (line in text.lineSequence()) {
                    if (ty > top + h - 4f) break
                    canvas.drawText(line, left + 6f, ty, tp)
                    ty += tp.textSize * 1.35f
                }
            }
            doc.finishPage(pdfPage)
        }
        outFile.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    /** 兼容调用: 不带文字层 */
    fun exportBitmaps(bitmaps: List<Bitmap>, outFile: File) =
        export(bitmaps.map { Page(it, null) }, outFile)
}

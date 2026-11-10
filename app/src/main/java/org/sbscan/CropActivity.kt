package org.sbscan

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.sbscan.imaging.EdgeDetector
import org.sbscan.imaging.Perspective
import org.sbscan.util.ImageIO
import org.sbscan.view.CropOverlayView
import java.io.File

/**
 * 裁剪页: 自动检测文档边角, 支持四角拖拽微调, 旋转, 确认透视矫正。
 */
class CropActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlay: CropOverlayView
    private lateinit var bitmap: Bitmap
    private var srcFile: File? = null

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_DOC = "docId"

        fun start(activity: Activity, imagePath: String, quad: Array<PointF>?, docId: String? = null) {
            val intent = Intent(activity, CropActivity::class.java)
                .putExtra(EXTRA_PATH, imagePath)
                .putExtra(EXTRA_DOC, docId)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        imageView = findViewById(R.id.image)
        overlay = findViewById(R.id.overlay)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        srcFile = File(path)
        bitmap = ImageIO.decodeSampled(srcFile!!, 2400)
        imageView.setImageBitmap(bitmap)

        imageView.post { initQuad() }

        findViewById<Button>(R.id.btn_rotate).setOnClickListener {
            bitmap = Perspective.rotate90(bitmap)
            imageView.setImageBitmap(bitmap)
            imageView.post { initQuad(forceFull = true) }
        }
        findViewById<Button>(R.id.btn_confirm).setOnClickListener { confirm() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    private fun initQuad(forceFull: Boolean = false) {
        val w = imageView.width.toFloat(); val h = imageView.height.toFloat()
        if (w <= 0 || h <= 0) return
        val scale = minOf(w / bitmap.width, h / bitmap.height)
        val dx = (w - bitmap.width * scale) / 2
        val dy = (h - bitmap.height * scale) / 2

        // 自动检测, 映射到视图坐标; 失败则整幅
        val detected = if (forceFull) null else EdgeDetector.detectQuad(bitmap)
        val quad = detected?.let { src ->
            Array(4) { PointF(src[it].x * scale + dx, src[it].y * scale + dy) }
        } ?: arrayOf(
            PointF(dx, dy),
            PointF(dx + bitmap.width * scale, dy),
            PointF(dx + bitmap.width * scale, dy + bitmap.height * scale),
            PointF(dx, dy + bitmap.height * scale)
        )
        overlay.interactive = true
        overlay.quad = quad
    }

    private fun confirm() {
        val q = overlay.quad ?: return
        // 视图坐标 -> 位图坐标
        val w = imageView.width.toFloat(); val h = imageView.height.toFloat()
        val scale = minOf(w / bitmap.width, h / bitmap.height)
        val dx = (w - bitmap.width * scale) / 2
        val dy = (h - bitmap.height * scale) / 2
        val srcQuad = Array(4) {
            PointF((q[it].x - dx) / scale, (q[it].y - dy) / scale)
        }
        val warped = try {
            Perspective.warp(bitmap, srcQuad)
        } catch (e: Exception) {
            Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(cacheDir, "warped_${System.currentTimeMillis()}.jpg")
        ImageIO.saveJpeg(warped, file)
        val docId = intent.getStringExtra(EXTRA_DOC)
        PageActivity.start(this, file.absolutePath, docId)
        finish()
    }
}

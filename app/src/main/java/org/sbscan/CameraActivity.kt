package org.sbscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Size
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.sbscan.imaging.EdgeDetector
import org.sbscan.util.ImageIO
import org.sbscan.view.CropOverlayView
import java.io.File
import java.util.concurrent.Executors

/**
 * 相机扫描:
 *  - 实时文档边缘检测(取景框内显示)
 *  - 稳定性判定自动拍摄(对标 CamScanner 会员的自动拍摄)
 *  - 手动快门 / 闪光灯 / 从相册导入
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: CropOverlayView
    private lateinit var imageCapture: ImageCapture
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var autoShoot = true
    private var capturing = false
    private var stableCount = 0
    private var lastQuad: Array<PointF>? = null
    private var camera: androidx.camera.core.Camera? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.permission_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
            val bmp = ImageIO.decodeSampled(bytes, 2400)
            val file = File(cacheDir, "import_${System.currentTimeMillis()}.jpg")
            ImageIO.saveJpeg(bmp, file)
            CropActivity.start(this, file.absolutePath, null)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)

        findViewById<ImageButton>(R.id.btn_shoot).setOnClickListener { shoot() }
        findViewById<ImageButton>(R.id.btn_import).setOnClickListener { importLauncher.launch("image/*") }
        findViewById<ImageButton>(R.id.btn_auto).setOnClickListener {
            autoShoot = !autoShoot
            (it as ImageButton).alpha = if (autoShoot) 1f else 0.4f
            Toast.makeText(this, if (autoShoot) "自动拍摄: 开" else "自动拍摄: 关", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btn_flash).setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (cam.cameraInfo.hasFlashUnit()) {
                val on = cam.cameraInfo.torchState.value == androidx.camera.core.TorchState.ON
                cam.cameraControl.enableTorch(!on)
                it.alpha = if (!on) 1f else 0.4f
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else
            permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyze)

            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** 实时边缘检测 + 稳定性自动快门 */
    private fun analyze(image: ImageProxy) {
        try {
            val bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val oriented = if (rot != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } else bmp

            val quad = EdgeDetector.detectQuad(oriented)

            // 稳定性判定: 连续多帧四角位移小于阈值则自动拍摄
            if (autoShoot && quad != null && !capturing) {
                val prev = lastQuad
                if (prev != null && quadsClose(prev, quad, oriented)) {
                    stableCount++
                    if (stableCount >= 5) {
                        stableCount = 0
                        runOnUiThread { shoot() }
                    }
                } else {
                    stableCount = 0
                }
            } else {
                stableCount = 0
            }
            lastQuad = quad

            // 映射到预览视图坐标
            val viewQuad = quad?.let { mapToView(it, oriented.width, oriented.height) }
            runOnUiThread {
                if (capturing) overlay.quad = null else overlay.quad = viewQuad
            }
            if (oriented !== bmp) oriented.recycle()
            bmp.recycle()
        } catch (e: Exception) {
            // 分析帧失败不影响预览
        } finally {
            image.close()
        }
    }

    private fun quadsClose(a: Array<PointF>, b: Array<PointF>, bmp: Bitmap): Boolean {
        val diag = Math.hypot(bmp.width.toDouble(), bmp.height.toDouble())
        var sum = 0.0
        for (i in 0..3) sum += Math.hypot((a[i].x - b[i].x).toDouble(), (a[i].y - b[i].y).toDouble())
        return sum / 4 < diag * 0.02
    }

    private fun mapToView(quad: Array<PointF>, srcW: Int, srcH: Int): Array<PointF> {
        // PreviewView 默认 FILL_CENTER: 铺满裁切, 用 max 缩放系数
        val vw = previewView.width.toFloat(); val vh = previewView.height.toFloat()
        if (vw <= 0 || vh <= 0) return quad
        val scale = maxOf(vw / srcW, vh / srcH)
        val dx = (vw - srcW * scale) / 2; val dy = (vh - srcH * scale) / 2
        return Array(4) { PointF(quad[it].x * scale + dx, quad[it].y * scale + dy) }
    }

    private fun shoot() {
        if (capturing) return
        capturing = true
        overlay.quad = null
        val file = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    CropActivity.start(this@CameraActivity, file.absolutePath, lastQuad)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(this@CameraActivity, "拍摄失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}

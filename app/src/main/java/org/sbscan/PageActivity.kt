package org.sbscan

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sbscan.data.DocStore
import org.sbscan.imaging.Filters
import org.sbscan.util.ImageIO
import java.io.File

/**
 * 页面处理页: 选择滤镜(增强/灰度/黑白/锐化), 预览满意后保存进文档。
 */
class PageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var srcBitmap: Bitmap
    private var current: Bitmap? = null
    private var mode: Filters.Mode = Filters.Mode.ENHANCE
    private var docId: String? = null

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_DOC = "docId"

        fun start(activity: Activity, imagePath: String, docId: String?) {
            activity.startActivity(
                Intent(activity, PageActivity::class.java)
                    .putExtra(EXTRA_PATH, imagePath)
                    .putExtra(EXTRA_DOC, docId)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)

        imageView = findViewById(R.id.image)
        progress = findViewById(R.id.progress)
        docId = intent.getStringExtra(EXTRA_DOC)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        srcBitmap = ImageIO.decodeSampled(File(path), 2400)

        setupChips()
        applyFilter()

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    private fun setupChips() {
        val container = findViewById<LinearLayout>(R.id.chips)
        val modes = listOf(
            Filters.Mode.ENHANCE to getString(R.string.filter_enhance),
            Filters.Mode.GRAY to getString(R.string.filter_gray),
            Filters.Mode.BW to getString(R.string.filter_bw),
            Filters.Mode.SHARPEN to getString(R.string.filter_sharpen),
            Filters.Mode.ORIGINAL to getString(R.string.filter_original)
        )
        modes.forEach { (m, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = m == mode
                setOnClickListener {
                    mode = m
                    for (i in 0 until container.childCount) (container.getChildAt(i) as? Chip)?.isChecked = (i == container.indexOfChild(this))
                    applyFilter()
                }
            }
            container.addView(chip)
        }
    }

    private fun applyFilter() {
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { Filters.apply(srcBitmap, mode) }
            current = result
            imageView.setImageBitmap(result)
            progress.visibility = android.view.View.GONE
        }
    }

    private fun save() {
        val bmp = current ?: return
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val store = DocStore(this@PageActivity)
                val d = docId?.let { store.get(it) } ?: store.create()
                store.addPage(d, bmp)
                d
            }
            progress.visibility = android.view.View.GONE
            DocActivity.start(this@PageActivity, doc.id)
            finish()
        }
    }
}

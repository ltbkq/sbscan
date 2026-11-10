package org.sbscan

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sbscan.data.DocStore
import org.sbscan.imaging.Filters
import org.sbscan.imaging.PdfExporter
import org.sbscan.util.ImageIO
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 文档页: 页面管理 / 重命名 / 导出可搜索 PDF / 分享 / 本地 OCR。
 */
class DocActivity : AppCompatActivity() {

    private lateinit var store: DocStore
    private lateinit var doc: DocStore.Doc
    private lateinit var adapter: PageAdapter

    companion object {
        private const val EXTRA_DOC = "docId"
        fun start(activity: Activity, docId: String) {
            activity.startActivity(Intent(activity, DocActivity::class.java).putExtra(EXTRA_DOC, docId))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doc)
        store = DocStore(this)
        val id = intent.getStringExtra(EXTRA_DOC) ?: run { finish(); return }
        doc = store.get(id) ?: run { finish(); return }

        title = doc.name

        val recycler = findViewById<RecyclerView>(R.id.pages)
        recycler.layoutManager = GridLayoutManager(this, 3)
        adapter = PageAdapter()
        recycler.adapter = adapter

        findViewById<Button>(R.id.btn_add).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btn_pdf).setOnClickListener { exportPdf() }
        findViewById<Button>(R.id.btn_rename).setOnClickListener { rename() }
    }

    override fun onResume() {
        super.onResume()
        doc = store.get(doc.id) ?: return
        adapter.notifyDataSetChanged()
    }

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.thumb)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

        override fun getItemCount() = doc.pages().size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = doc.pages()[position]
            holder.label.text = "第 ${position + 1} 页"
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(file.absolutePath, o)
                }
                holder.img.setImageBitmap(bmp)
            }
            holder.itemView.setOnClickListener { pageMenu(file, position) }
            holder.itemView.setOnLongClickListener {
                pageMenu(file, position); true
            }
        }
    }

    private fun pageMenu(file: File, position: Int) {
        val items = arrayOf("识别文字(OCR)", "增强预览", "删除此页")
        AlertDialog.Builder(this)
            .setTitle("第 ${position + 1} 页")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ocr(file)
                    1 -> PageActivity.start(this, file.absolutePath, doc.id)
                    2 -> {
                        store.deletePage(file)
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    /** 本地 OCR(无限次, 无云端计费) */
    private fun ocr(file: File) {
        Toast.makeText(this, R.string.processing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { store.loadPage(file, 1600) }
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                val text = suspendCoroutine { cont ->
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { cont.resume(it.text) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                showOcrResult(text.ifEmpty { "(未识别到文字)" })
                recognizer.close()
            } catch (e: Exception) {
                Toast.makeText(this@DocActivity, "OCR 失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOcrResult(text: String) {
        val view = layoutInflater.inflate(R.layout.dialog_ocr, null)
        val edit = view.findViewById<EditText>(R.id.ocr_text)
        edit.setText(text)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("sbscan-ocr", edit.text.toString()))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.btn_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 导出可搜索 PDF(图片 + 隐形文字层) */
    private fun exportPdf() {
        Toast.makeText(this, R.string.processing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.Default) {
                    val pages = doc.pages().map { f ->
                        val bmp = store.loadPage(f, 1600)
                        val text = runCatching { ocrSync(bmp) }.getOrNull()
                        PdfExporter.Page(bmp, text)
                    }
                    val outFile = File(store.exportDir(), "${doc.name}.pdf")
                    PdfExporter.export(pages, outFile)
                    outFile
                }
                shareFile(out, "application/pdf")
            } catch (e: Exception) {
                Toast.makeText(this@DocActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun ocrSync(bmp: Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return suspendCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private fun shareFile(file: File, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享"))
    }

    private fun rename() {
        val edit = EditText(this).apply {
            setText(doc.name)
            hint = getString(R.string.doc_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                store.rename(doc, edit.text.toString().ifEmpty { doc.name })
                title = doc.name
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

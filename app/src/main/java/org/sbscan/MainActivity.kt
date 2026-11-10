package org.sbscan

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sbscan.data.DocStore

/**
 * 主页: 文档列表。点击进入文档, 长按重命名/删除, FAB 开始扫描。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: DocStore
    private lateinit var adapter: DocAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = DocStore(this)

        emptyView = findViewById(R.id.empty)
        val recycler = findViewById<RecyclerView>(R.id.docs)
        recycler.layoutManager = GridLayoutManager(this, 2)
        adapter = DocAdapter()
        recycler.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.fab_scan).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) { store.list() }
            adapter.submit(docs)
            emptyView.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    inner class DocAdapter : RecyclerView.Adapter<DocAdapter.VH>() {
        private var items: List<DocStore.Doc> = emptyList()
        fun submit(list: List<DocStore.Doc>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.cover)
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_doc, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val doc = items[position]
            holder.name.text = doc.name
            val pages = doc.pages()
            holder.count.text = "${pages.size} 页"
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    pages.firstOrNull()?.let {
                        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(it.absolutePath, o)
                    }
                }
                holder.img.setImageBitmap(bmp)
            }
            holder.itemView.setOnClickListener { DocActivity.start(this@MainActivity, doc.id) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(doc.name)
                    .setItems(arrayOf("重命名", "删除")) { _, which ->
                        when (which) {
                            0 -> rename(doc)
                            1 -> {
                                AlertDialog.Builder(this@MainActivity)
                                    .setMessage("删除文档「${doc.name}」？")
                                    .setPositiveButton("删除") { _, _ ->
                                        lifecycleScope.launch {
                                            withContext(Dispatchers.IO) { store.delete(doc) }
                                            refresh()
                                        }
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        }
                    }
                    .show()
                true
            }
        }
    }

    private fun rename(doc: DocStore.Doc) {
        val edit = android.widget.EditText(this).apply {
            setText(doc.name)
            hint = getString(R.string.doc_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.rename(doc, edit.text.toString().ifEmpty { doc.name }) }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

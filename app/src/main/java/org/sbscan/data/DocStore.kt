package org.sbscan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.sbscan.util.ImageIO
import java.io.File

/**
 * 本地文档存储: filesDir/docs/<docId>/page_xxx.jpg + meta.json
 * 零数据库、零云——所有数据只存在于应用私有目录。
 */
class DocStore(context: Context) {

    data class Doc(val dir: File) {
        val id: String get() = dir.name
        val name: String get() = dir.resolve("meta.json").takeIf { it.exists() }
            ?.readText()?.trim('"')?.takeIf { it.isNotEmpty() } ?: dir.name
        fun pages(): List<File> = dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
        fun cover(): File? = pages().firstOrNull()
        fun lastModified(): Long = dir.lastModified()
    }

    private val root = File(context.filesDir, "docs").apply { mkdirs() }
    private val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }

    fun exportDir(): File = exportDir

    fun list(): List<Doc> = root.listFiles { f -> f.isDirectory }?.map { Doc(it) }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun get(id: String): Doc? = File(root, id).takeIf { it.isDirectory }?.let { Doc(it) }

    fun create(name: String = defaultName()): Doc {
        var dir = File(root, System.currentTimeMillis().toString())
        dir.mkdirs()
        dir.resolve("meta.json").writeText("\"$name\"")
        return Doc(dir)
    }

    fun rename(doc: Doc, newName: String) {
        doc.dir.resolve("meta.json").writeText("\"$newName\"")
    }

    fun delete(doc: Doc) {
        doc.dir.deleteRecursively()
    }

    fun addPage(doc: Doc, bitmap: Bitmap): File {
        val index = (doc.pages().size + 1)
        val file = File(doc.dir, "page_%03d.jpg".format(index))
        ImageIO.saveJpeg(bitmap, file)
        doc.dir.setLastModified(System.currentTimeMillis())
        return file
    }

    fun deletePage(file: File) {
        file.delete()
    }

    /** 页面排序: 将 fromIndex 页移到 toIndex, 重新编号 */
    fun movePage(doc: Doc, fromIndex: Int, toIndex: Int) {
        val pages = doc.pages().toMutableList()
        if (fromIndex !in pages.indices || toIndex !in pages.indices) return
        val moved = pages.removeAt(fromIndex)
        pages.add(toIndex, moved)
        // 重编号: 先挪到临时名避免冲突
        pages.forEachIndexed { i, f ->
            File(doc.dir, "tmp_%03d.jpg".format(i)).also { f.renameTo(it) }
        }
        pages.forEachIndexed { i, _ ->
            File(doc.dir, "tmp_%03d.jpg".format(i)).renameTo(File(doc.dir, "page_%03d.jpg".format(i + 1)))
        }
        doc.dir.setLastModified(System.currentTimeMillis())
    }

    fun loadPage(file: File, maxDim: Int = 2048): Bitmap = ImageIO.decodeSampled(file, maxDim)

    private fun defaultName(): String {
        val fmt = java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.CHINA)
        return "文档 " + fmt.format(java.util.Date())
    }
}

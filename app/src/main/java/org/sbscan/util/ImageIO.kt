package org.sbscan.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ImageIO {

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** 按最大边采样解码 */
    fun decodeSampled(file: File, maxDim: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > maxDim * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, o2)
            ?: throw IllegalStateException("decode failed: ${file.absolutePath}")
        return applyExifRotation(file, bmp)
    }

    fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > maxDim * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            ?: throw IllegalStateException("decode failed")
    }

    private fun applyExifRotation(file: File, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) bmp else {
                val m = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
        } catch (e: Exception) {
            bmp
        }
    }
}

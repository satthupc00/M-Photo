package com.mondiro.mphoto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageEdit {
    /** Định dạng lưu được sau khi xoay/lật. */
    fun canSave(mime: String): Boolean = mime.lowercase() in setOf("image/jpeg", "image/jpg", "image/png", "image/webp")

    /**
     * Ghi phép xoay/lật vào file.
     * rotation: số độ xoay theo chiều kim đồng hồ (bội số của 90), flip: lật ngang (áp dụng sau khi xoay).
     * JPEG: chỉ sửa thông tin hướng (EXIF), không giảm chất lượng ảnh.
     * PNG / WebP: vẽ lại ảnh ở chất lượng gốc (không nén mất dữ liệu).
     */
    suspend fun save(context: Context, item: MediaItem, rotation: Int, flip: Boolean) = withContext(Dispatchers.IO) {
        val rot = ((rotation % 360) + 360) % 360
        val r = context.contentResolver
        val mime = item.mime.lowercase()
        if (mime == "image/jpeg" || mime == "image/jpg") {
            r.openFileDescriptor(item.uri, "rw")!!.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (rot != 0) exif.rotate(rot)
                if (flip) exif.flipHorizontally()
                exif.saveAttributes()
            }
        } else {
            val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(r, item.uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
            }
            val m = Matrix().apply {
                setRotate(rot.toFloat())
                if (flip) postScale(-1f, 1f)
            }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            r.openOutputStream(item.uri, "wt")!!.use { os ->
                if (mime == "image/png") out.compress(Bitmap.CompressFormat.PNG, 100, os)
                else out.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, os)
            }
            if (out !== src) out.recycle()
            src.recycle()
        }
    }
}

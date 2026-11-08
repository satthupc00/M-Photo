package com.mondiro.mphoto.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copy / di chuyển ảnh, video giữa các thư mục qua MediaStore (không cần quyền quản lý toàn bộ bộ nhớ). */
object FileOps {
    private val IMAGE_TOPS = listOf("DCIM", "Pictures", "Download")
    private val VIDEO_TOPS = listOf("DCIM", "Movies", "Pictures", "Download")

    /** Chuẩn hoá đường dẫn đích: bỏ "/" thừa, sửa hoa/thường của thư mục gốc (dcim -> DCIM). */
    fun normalize(path: String): String {
        val p = path.trim().trim('/').replace(Regex("/+"), "/")
        if (p.isEmpty()) return p
        val top = p.substringBefore('/')
        val fixed = (IMAGE_TOPS + VIDEO_TOPS).firstOrNull { it.equals(top, true) } ?: top
        return if (p.contains('/')) fixed + "/" + p.substringAfter('/') else fixed
    }

    /** Android chỉ cho app ghi ảnh vào DCIM, Pictures, Download; video thêm Movies. */
    fun canPlace(dest: String, isVideo: Boolean): Boolean {
        val top = normalize(dest).substringBefore('/')
        return (if (isVideo) VIDEO_TOPS else IMAGE_TOPS).any { it.equals(top, true) }
    }

    fun allowedTops(isVideo: Boolean) = if (isVideo) VIDEO_TOPS else IMAGE_TOPS

    private fun collectionFor(dest: String, isVideo: Boolean): Uri = when {
        normalize(dest).startsWith("Download") -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    suspend fun copy(context: Context, item: MediaItem, dest: String): Uri = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        val d = normalize(dest)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$d/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!d.startsWith("Download")) put(MediaStore.MediaColumns.DATE_TAKEN, item.date)
        }
        val out = r.insert(collectionFor(d, item.isVideo), values) ?: throw IllegalStateException("không tạo được file mới")
        try {
            r.openInputStream(item.uri)!!.use { i -> r.openOutputStream(out)!!.use { o -> i.copyTo(o, 256 * 1024) } }
            r.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { r.delete(out, null, null) } catch (_: Exception) {}
            throw e
        }
        out
    }

    /** Cần quyền ghi (createWriteRequest) cho item trước khi gọi. */
    suspend fun move(context: Context, item: MediaItem, dest: String) = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        val d = normalize(dest)
        val sameCollection = !d.startsWith("Download")
        if (sameCollection) {
            val ok = try {
                r.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$d/") }, null, null) > 0
            } catch (_: Exception) { false }
            if (ok) return@withContext
        }
        // Khác loại thư mục (vd vào Download): chép sang rồi xoá bản gốc
        copy(context, item, d)
        r.delete(item.uri, null, null)
    }
}

package com.mondiro.mphoto.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val folder: String,
    val name: String,
    val date: Long,
    val isVideo: Boolean,
    val duration: Long,
    val mime: String,
    val size: Long,
    val width: Int,
    val height: Int,
)

class Folder(val path: String, val items: List<MediaItem>) {
    val name: String get() = path.substringAfterLast('/').ifEmpty { "Bộ nhớ trong" }
    val count: Int get() = items.size
    val videoCount: Int = items.count { it.isVideo }
    val cover: MediaItem? get() = items.firstOrNull()
    val latest: Long get() = items.firstOrNull()?.date ?: 0L
}

object MediaRepository {
    private const val ROOT_LABEL = "Bộ nhớ trong"

    suspend fun load(context: Context): Map<String, Folder> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.VOLUME_NAME,
        )
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val list = ArrayList<MediaItem>(4096)
        context.contentResolver.query(uri, proj, sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0])
            val iType = c.getColumnIndexOrThrow(proj[1])
            val iRel = c.getColumnIndexOrThrow(proj[2])
            val iName = c.getColumnIndexOrThrow(proj[3])
            val iTaken = c.getColumnIndexOrThrow(proj[4])
            val iMod = c.getColumnIndexOrThrow(proj[5])
            val iDur = c.getColumnIndexOrThrow(proj[6])
            val iMime = c.getColumnIndexOrThrow(proj[7])
            val iSize = c.getColumnIndexOrThrow(proj[8])
            val iW = c.getColumnIndexOrThrow(proj[9])
            val iH = c.getColumnIndexOrThrow(proj[10])
            val iVol = c.getColumnIndexOrThrow(proj[11])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val vol = c.getString(iVol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                var rel = (c.getString(iRel) ?: "").trim('/')
                if (vol != MediaStore.VOLUME_EXTERNAL_PRIMARY) rel = if (rel.isEmpty()) "Thẻ nhớ" else "Thẻ nhớ/$rel"
                if (rel.isEmpty()) rel = ROOT_LABEL
                val base = if (video) MediaStore.Video.Media.getContentUri(vol) else MediaStore.Images.Media.getContentUri(vol)
                val taken = c.getLong(iTaken)
                list += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    folder = rel,
                    name = c.getString(iName) ?: "",
                    date = if (taken > 0) taken else c.getLong(iMod) * 1000L,
                    isVideo = video,
                    duration = c.getLong(iDur),
                    mime = c.getString(iMime) ?: if (video) "video/*" else "image/*",
                    size = c.getLong(iSize),
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                )
            }
        }
        list.sortByDescending { it.date }
        list.groupBy { it.folder }.mapValues { (k, v) -> Folder(k, v) }
    }
}

package com.mondiro.mphoto.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 1 file hoặc thư mục trong tab File. Thời gian tính bằng mili giây. */
data class FileEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val added: Long = modified,
    val children: Int = 0,
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val file: File get() = File(path)
    val mime: String get() = if (isDir) "" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
        "json", "atlas", "md", "log", "csv" -> "text/plain"
        else -> "application/octet-stream"
    }
    val kind: FileKind get() = FileKind.of(this)
}

/** Nhóm loại file: màu ô biểu tượng + nhãn ngắn + tên chip lọc. */
enum class FileKind(val color: Color, val chip: String) {
    Folder(Color(0xFF3A3320), "Thư mục"),
    Image(Color(0xFF2F6FED), "Ảnh"),
    Video(Color(0xFF9B3BD6), "Video"),
    Audio(Color(0xFFC2410C), "Âm thanh"),
    Doc(Color(0xFFE5383B), "Tài liệu"),
    Design(Color(0xFF7B5CFF), "Thiết kế"),
    Apk(Color(0xFF22A06B), "APK"),
    Archive(Color(0xFFE8930A), "File nén"),
    Other(Color(0xFF5A5F69), "Khác");

    companion object {
        private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt", "ods", "odp", "hwp", "pages", "numbers", "key", "epub")
        private val DESIGN = setOf("psd", "psb", "ai", "xd", "fig", "sketch", "blend", "fbx", "obj", "ma", "mb", "max", "c4d", "spine", "skel", "atlas", "kra", "clip", "procreate", "aep", "prproj", "svg", "tga", "exr", "hdr", "fire", "anim", "prefab")
        private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
        private val AUDIO = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr", "mid", "midi")

        fun of(e: FileEntry): FileKind {
            if (e.isDir) return Folder
            val x = e.ext
            return when {
                x == "apk" || x == "apks" || x == "xapk" -> Apk
                x in ARCHIVE -> Archive
                x in DESIGN -> Design
                x == "json" && e.name.contains("spine", true) -> Design
                x in DOC -> Doc
                x in AUDIO -> Audio
                e.mime.startsWith("image/") -> Image
                e.mime.startsWith("video/") -> Video
                e.mime.startsWith("audio/") -> Audio
                else -> Other
            }
        }
    }
}

object Files {
    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Đường dẫn tương đối so với bộ nhớ trong ("" = gốc). */
    fun rel(path: String): String = path.removePrefix(root.absolutePath).trim('/')

    fun storage(): Pair<Long, Long> = try {
        val s = StatFs(root.absolutePath)
        (s.blockCountLong - s.availableBlocksLong) * s.blockSizeLong to s.blockCountLong * s.blockSizeLong
    } catch (_: Exception) { 0L to 0L }

    suspend fun list(dir: File, showHidden: Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
        (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                val d = f.isDirectory
                FileEntry(
                    path = f.absolutePath, name = f.name, isDir = d,
                    size = if (d) 0 else f.length(), modified = f.lastModified(),
                    children = if (d) (f.list()?.count { showHidden || !it.startsWith(".") } ?: 0) else 0,
                )
            }
    }

    /** File mới trong [days] ngày ở mọi thư mục (đọc từ chỉ mục của Android nên rất nhanh). */
    suspend fun recent(context: Context, days: Int, skip: (relDir: String) -> Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() / 1000 - days * 86400L
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE,
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 800)
        }
        val out = ArrayList<FileEntry>()
        context.contentResolver.query(uri, proj, args, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")) continue
                val relDir = rel(path).substringBeforeLast('/', "")
                if (relDir.isNotEmpty() && skip(relDir)) continue
                val f = File(path)
                if (!f.exists() || f.isDirectory) continue
                out += FileEntry(
                    path = path, name = c.getString(1) ?: f.name, isDir = false,
                    size = c.getLong(2), modified = c.getLong(4) * 1000, added = c.getLong(3) * 1000,
                )
            }
        }
        out
    }

    /** Báo cho Android cập nhật lại chỉ mục (để thư viện ảnh / Gần đây thấy thay đổi ngay). */
    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val all = ArrayList<String>()
        for (p in paths) {
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().take(3000).forEach { all += it.absolutePath } else all += p
        }
        MediaScannerConnection.scanFile(context, all.toTypedArray(), null, null)
    }

    /** Tên chưa trùng trong thư mục đích: "abc.png" -> "abc (1).png". */
    fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    suspend fun copy(src: File, destDir: File): File = withContext(Dispatchers.IO) {
        if (destDir.absolutePath.startsWith(src.absolutePath + "/") || destDir.absolutePath == src.absolutePath)
            throw IllegalStateException("không chép thư mục vào chính nó")
        val t = uniqueTarget(destDir, src.name)
        if (src.isDirectory) src.copyRecursively(t) else src.copyTo(t)
        t
    }

    suspend fun move(src: File, destDir: File): File = withContext(Dispatchers.IO) {
        if (destDir.absolutePath.startsWith(src.absolutePath + "/") || destDir.absolutePath == src.absolutePath)
            throw IllegalStateException("không chuyển thư mục vào chính nó")
        if (src.parentFile?.absolutePath == destDir.absolutePath) return@withContext src
        val t = uniqueTarget(destDir, src.name)
        if (!src.renameTo(t)) {
            if (src.isDirectory) src.copyRecursively(t) else src.copyTo(t)
            if (!src.deleteRecursively()) throw IllegalStateException("đã chép nhưng không xoá được bản gốc")
        }
        t
    }

    suspend fun delete(f: File): Boolean = withContext(Dispatchers.IO) { f.deleteRecursively() }

    suspend fun rename(f: File, newName: String): File = withContext(Dispatchers.IO) {
        val n = newName.trim()
        if (n.isEmpty() || n.contains('/')) throw IllegalStateException("tên không hợp lệ")
        val t = File(f.parentFile, n)
        if (t.exists()) throw IllegalStateException("đã có file tên này")
        if (!f.renameTo(t)) throw IllegalStateException("không đổi tên được")
        t
    }
}

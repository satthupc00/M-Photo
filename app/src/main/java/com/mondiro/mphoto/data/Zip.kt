package com.mondiro.mphoto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

object Zip {
    /**
     * Giải nén [zip] vào thư mục mới cùng tên (vd "abc.zip" -> "abc/", trùng thì "abc (1)/").
     * Thử bảng mã UTF-8 trước, tên file lỗi thì thử lại bảng mã cũ của Windows (CP437).
     * Chặn file có đường dẫn trỏ ra ngoài thư mục đích (zip-slip).
     */
    suspend fun unzip(zip: File, onProgress: suspend (done: Int, total: Int) -> Unit): File = withContext(Dispatchers.IO) {
        val parent = zip.parentFile ?: throw IllegalStateException("không rõ thư mục chứa")
        val dest = Files.uniqueTarget(parent, zip.name.substringBeforeLast('.'))
        var last: Exception? = null
        for (cs in listOf(Charsets.UTF_8, Charset.forName("CP437"))) {
            try {
                extract(zip, dest, cs, onProgress)
                return@withContext dest
            } catch (e: Exception) {
                last = e
                dest.deleteRecursively()
                val msg = e.message.orEmpty()
                // Chỉ thử bảng mã khác khi lỗi do tên file
                if (!(e is IllegalArgumentException || msg.contains("MALFORMED", true) || msg.contains("invalid", true))) break
            }
        }
        val m = last?.message.orEmpty()
        throw IllegalStateException(
            when {
                m.contains("encrypt", true) -> "file ZIP có mật khẩu, chưa hỗ trợ"
                else -> last?.message ?: "file ZIP bị lỗi"
            }
        )
    }

    private suspend fun extract(zip: File, dest: File, cs: Charset, onProgress: suspend (Int, Int) -> Unit) {
        ZipFile(zip, cs).use { zf ->
            val entries = zf.entries().toList()
            val total = entries.size
            val root = dest.canonicalPath + File.separator
            dest.mkdirs()
            entries.forEachIndexed { i, e ->
                val out = File(dest, e.name)
                if (!(out.canonicalPath + (if (e.isDirectory) File.separator else "")).startsWith(root) && out.canonicalPath != dest.canonicalPath)
                    throw IllegalStateException("file ZIP chứa đường dẫn không an toàn")
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { i2 -> out.outputStream().use { o -> i2.copyTo(o, 256 * 1024) } }
                    if (e.time > 0) out.setLastModified(e.time)
                }
                if (i % 5 == 0 || i == total - 1) onProgress(i + 1, total)
            }
        }
    }
}

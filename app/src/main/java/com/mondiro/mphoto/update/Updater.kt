package com.mondiro.mphoto.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mondiro.mphoto.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String)

sealed interface UpdState {
    data object Idle : UpdState
    data object Checking : UpdState
    data object Latest : UpdState
    data class Available(val release: Release) : UpdState
    data class Downloading(val progress: Float) : UpdState
    data object Error : UpdState
}

object Updater {
    private const val API = "https://api.github.com/repos/satthupc00/m-photo/releases/latest"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MPhoto-Android")
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            Release(
                version = o.optString("tag_name").removePrefix("v"),
                notes = o.optString("body"),
                apkUrl = apk,
                pageUrl = o.optString("html_url"),
            )
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(remote: String, local: String = currentVersion): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "MPhoto.apk")
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MPhoto-Android")
        // GitHub chuyển hướng sang máy chủ khác -> tự theo
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val loc = conn.getHeaderField("Location"); conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MPhoto-Android")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        conn.disconnect()
        out
    }

    /** Trả về false nếu cần người dùng cấp quyền "cài app không rõ nguồn" trước. */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

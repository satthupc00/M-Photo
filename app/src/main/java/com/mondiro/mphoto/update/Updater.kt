package com.mondiro.mphoto.update

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mondiro.mphoto.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** Tải APK bằng DownloadManager của Android (tự theo chuyển hướng của GitHub, hiện thông báo tải). */
    suspend fun download(context: Context, url: String, version: String, onProgress: suspend (Float) -> Unit): File {
        val dm = context.getSystemService(DownloadManager::class.java)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "dl")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, "MPhoto-v$version.apk")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("M Photo v$version")
            .setDescription("Đang tải bản cập nhật")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(dest))
        val id = dm.enqueue(req)
        try {
            while (true) {
                delay(400)
                val (status, done, total, reason) = withContext(Dispatchers.IO) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) null
                        else listOf(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).toLong(),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)).toLong(),
                        )
                    } ?: throw IllegalStateException("đã bị huỷ")
                }
                if (total > 0) onProgress(done.toFloat() / total)
                when (status.toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> return dest
                    DownloadManager.STATUS_FAILED -> throw IllegalStateException("mã lỗi $reason")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            dm.remove(id); throw e
        }
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
        // Dùng PackageInstaller qua ACTION_VIEW; nếu máy không mở được thì để người dùng tải bằng trình duyệt
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

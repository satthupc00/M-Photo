package com.mondiro.mphoto.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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

    /** Tải APK: thử DownloadManager của Android trước, hỏng thì tải trực tiếp. Lỗi sẽ ghi rõ nguyên nhân của cả 2 cách. */
    suspend fun download(context: Context, url: String, version: String, onProgress: suspend (Float) -> Unit): File {
        val e1 = try {
            return downloadWithManager(context, url, version, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { e }
        try {
            return downloadDirect(context, url, version, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e2: Exception) {
            throw IllegalStateException("[1] ${describe(e1)}\n[2] ${describe(e2)}")
        }
    }

    fun describe(e: Throwable): String = (e::class.java.simpleName + ": " + (e.message ?: "")).take(160)

    private suspend fun downloadWithManager(context: Context, url: String, version: String, onProgress: suspend (Float) -> Unit): File {
        val dm = context.getSystemService(DownloadManager::class.java)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IllegalStateException("không có thư mục tải")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, "MPhoto-v$version.apk")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("M Photo v$version")
            .setDescription("Đang tải bản cập nhật")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(dest))
        val id = dm.enqueue(req)
        try {
            while (true) {
                delay(400)
                val row = withContext(Dispatchers.IO) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) null
                        else longArrayOf(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).toLong(),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)).toLong(),
                        )
                    }
                } ?: throw IllegalStateException("tải bị huỷ")
                if (row[2] > 0) onProgress(row[1].toFloat() / row[2])
                when (row[0].toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (!dest.exists() || dest.length() == 0L) throw IllegalStateException("file tải về trống")
                        return dest
                    }
                    DownloadManager.STATUS_FAILED -> { dm.remove(id); throw IllegalStateException("DownloadManager mã ${row[3]}") }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            dm.remove(id); throw e
        }
    }

    private suspend fun downloadDirect(context: Context, url: String, version: String, onProgress: suspend (Float) -> Unit): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "MPhoto-v$version.apk")
        var progress = 0f
        val job = withContext(Dispatchers.IO) {
            var u = url
            var conn: HttpURLConnection? = null
            var hops = 0
            while (true) {
                conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("User-Agent", "MPhoto-Android")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                val code = conn!!.responseCode
                if (code in 300..399) {
                    val loc = conn!!.getHeaderField("Location") ?: throw IllegalStateException("chuyển hướng thiếu địa chỉ")
                    conn!!.disconnect()
                    u = URL(URL(u), loc).toString()
                    if (++hops > 6) throw IllegalStateException("chuyển hướng quá nhiều")
                    continue
                }
                if (code != 200) throw IllegalStateException("HTTP $code")
                break
            }
            val c = conn!!
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress = done.toFloat() / total
                    }
                }
            }
            c.disconnect()
            out
        }
        onProgress(progress)
        return job
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
        // Cài bằng PackageInstaller của Android (cách chính thức, không cần mở file)
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { os ->
                file.inputStream().use { it.copyTo(os, 64 * 1024) }
                s.fsync(os)
            }
            val cb = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            s.commit(cb.intentSender)
        }
        return true
    }
}

/** Nhận kết quả cài đặt: cần người dùng xác nhận thì mở hộp thoại cài của Android. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { if (confirm != null) context.startActivity(confirm) } catch (e: Exception) {
                    Toast.makeText(context, "Không mở được hộp thoại cài: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED)
                    Toast.makeText(context, "Cài bản cập nhật lỗi ($status) $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}

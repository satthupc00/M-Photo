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

data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String, val apkSize: Long = -1)

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
            var size = -1L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); size = a.optLong("size", -1); break }
            }
            Release(
                version = o.optString("tag_name").removePrefix("v"),
                notes = o.optString("body"),
                apkUrl = apk,
                pageUrl = o.optString("html_url"),
                apkSize = size,
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

    /**
     * Tải APK. Cách 1: tải trực tiếp, dừng ngay khi đủ dung lượng (một số mạng giữ kết nối không đóng ở cuối file),
     * bị đứt thì tải tiếp phần còn thiếu. Cách 2: DownloadManager của Android. Lỗi sẽ ghi rõ nguyên nhân cả 2 cách.
     */
    suspend fun download(context: Context, url: String, version: String, expectedSize: Long, onProgress: suspend (Float) -> Unit): File {
        val e1 = try {
            return downloadDirect(context, url, version, expectedSize, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { e }
        try {
            return downloadWithManager(context, url, version, onProgress)
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
        var lastBytes = -1L
        var stallSince = System.currentTimeMillis()
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
                if (row[1] != lastBytes) { lastBytes = row[1]; stallSince = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - stallSince > 25000) { dm.remove(id); throw IllegalStateException("DownloadManager đứng ở ${row[1]}/${row[2]}") }
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

    private suspend fun downloadDirect(context: Context, url: String, version: String, expectedSize: Long, onProgress: suspend (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "MPhoto-v$version.apk")
            var total = expectedSize
            var lastErr: Exception? = null
            var lastReported = -1
            for (attempt in 1..8) {
                val have = out.length()
                if (total > 0 && have >= total) break
                try {
                    val c = open(url, if (have > 0) have else -1)
                    val code = c.responseCode
                    val append = have > 0 && code == 206
                    if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                    if (total <= 0) {
                        val len = c.contentLengthLong
                        total = if (len > 0) (if (append) have + len else len) else -1
                    }
                    var done = if (append) have else 0L
                    c.inputStream.use { input ->
                        java.io.FileOutputStream(out, append).use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                // Đủ dung lượng thì dừng luôn, không chờ máy chủ đóng kết nối
                                if (total > 0 && done >= total) break
                                val want = if (total > 0) minOf(buf.size.toLong(), total - done).toInt() else buf.size
                                val n = input.read(buf, 0, want)
                                if (n < 0) break
                                o.write(buf, 0, n); done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastReported) { lastReported = pct; withContext(Dispatchers.Main) { onProgress(pct / 100f) } }
                                }
                            }
                        }
                    }
                    c.disconnect()
                    if (total <= 0 || out.length() >= total) break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    kotlinx.coroutines.delay(1000L * attempt)
                }
            }
            if (total > 0 && out.length() < total) throw IllegalStateException("tải được ${out.length()}/$total byte" + (lastErr?.let { " · " + describe(it) } ?: ""))
            if (out.length() == 0L) throw lastErr ?: IllegalStateException("file tải về trống")
            out
        }

    /** Mở kết nối, tự theo chuyển hướng của GitHub; from >= 0 thì xin tải tiếp từ byte đó. */
    private fun open(url: String, from: Long): HttpURLConnection {
        var u = url
        repeat(7) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 12000
                setRequestProperty("User-Agent", "MPhoto-Android")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("chuyển hướng thiếu địa chỉ")
                c.disconnect()
                u = URL(URL(u), loc).toString()
            } else return c
        }
        throw IllegalStateException("chuyển hướng quá nhiều")
    }

    /** Cách cài dự phòng: mở file APK bằng trình cài của Android. */
    fun installViaView(context: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
        // Dọn các phiên cài cũ còn treo của app
        pi.mySessions.forEach { try { pi.abandonSession(it.sessionId) } catch (_: Exception) {} }
        InstallEvents.gotCallback = false
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

/** Kênh báo kết quả cài từ InstallReceiver về màn hình app (mở hộp thoại cài từ Activity để Android không chặn). */
object InstallEvents {
    val confirm = kotlinx.coroutines.flow.MutableStateFlow<Intent?>(null)
    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @Volatile var gotCallback = false
}

/** Nhận kết quả cài đặt: cần người dùng xác nhận thì chuyển hộp thoại cài cho màn hình app mở. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        InstallEvents.gotCallback = true
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                InstallEvents.confirm.value = confirm
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                InstallEvents.message.value = "Cài bản cập nhật lỗi ($status) $msg"
                Toast.makeText(context, "Cài bản cập nhật lỗi ($status) $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}

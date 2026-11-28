package com.mondiro.mphoto.ui

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mondiro.mphoto.data.Folder
import com.mondiro.mphoto.data.MediaItem
import com.mondiro.mphoto.data.MediaRepository
import com.mondiro.mphoto.data.Prefs
import com.mondiro.mphoto.data.Rules
import com.mondiro.mphoto.data.UserRule
import com.mondiro.mphoto.update.Release
import com.mondiro.mphoto.update.UpdState
import com.mondiro.mphoto.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

enum class Tab { Album, Folder, Files, Settings }

/** 1 nút trong chế độ Thư mục. own = ảnh nằm trực tiếp trong thư mục này. */
class Node(
    val path: String,
    val name: String,
    val count: Int,
    val videoCount: Int,
    val subCount: Int,
    val cover: MediaItem?,
    val own: Folder?,
)

data class ViewerState(val folder: String, val index: Int)
data class PickerReq(val items: List<MediaItem>, val move: Boolean, val defaultNew: String? = null)
class PermReq(val sender: android.content.IntentSender, val onGranted: () -> Unit)
const val EXTERNAL = "Mở từ app khác"

data class ToastMsg(val text: String, val id: Long = System.nanoTime())

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    var hasPermission by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadedOnce by mutableStateOf(false)
    var folders by mutableStateOf<Map<String, Folder>>(emptyMap())
        private set

    var tab by mutableStateOf(if (prefs.defaultTab == "folder") Tab.Folder else Tab.Album)
    var path by mutableStateOf<List<String>>(emptyList())
    var openFolder by mutableStateOf<String?>(null)
    var viewer by mutableStateOf<ViewerState?>(null)
    var search by mutableStateOf<String?>(null)
    var sheetFolder by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<String?>(null)
    var toast by mutableStateOf<ToastMsg?>(null)

    var showHidden by mutableStateOf(false)
    var pinned by mutableStateOf(prefs.pinned)
    var hidden by mutableStateOf(prefs.hidden)
    var gsync by mutableStateOf(prefs.gsync)
    var userRules by mutableStateOf(prefs.rules)
    var sysOff by mutableStateOf(prefs.sysOff)
    var autoExclude by mutableStateOf(prefs.autoExclude)
    var defaultTab by mutableStateOf(prefs.defaultTab)
    var panSpeed by mutableStateOf(prefs.panSpeed.also { UiPrefs.pan = it })
        private set
    var scrollSpeed by mutableStateOf(prefs.scrollSpeed)
        private set
    var albumSize by mutableStateOf(prefs.albumSize)
        private set

    fun setPan(v: Float) { panSpeed = v; UiPrefs.pan = v; prefs.panSpeed = v }
    fun setScroll(v: Float) { scrollSpeed = v; prefs.scrollSpeed = v }
    fun changeAlbumSize(v: Int) { albumSize = v.coerceIn(0, 2); prefs.albumSize = albumSize }

    // Giữ vị trí cuộn khi mở thư mục rồi quay ra, hoặc đổi tab
    val albumGridState = LazyGridState()
    private val folderGridStates = HashMap<String, LazyGridState>()
    /** Lưới ảnh của từng thư mục giữ vị trí cuộn riêng (mở lại thư mục là về đúng chỗ cũ). */
    fun folderGridState(path: String): LazyGridState = folderGridStates.getOrPut(path) { LazyGridState() }
    /** Ảnh xem gần nhất trong trình xem, để khi thoát ra lưới cuộn tới đúng ảnh đó. */
    var lastViewed by mutableStateOf<ViewerState?>(null)

    /** Đổi cỡ ô (dùng chung cho Album và lưới ảnh) theo 1 bậc: +1 to hơn, -1 nhỏ hơn. */
    fun stepSize(d: Int): Boolean {
        val n = (albumSize + d).coerceIn(0, 2)
        if (n == albumSize) return false
        changeAlbumSize(n); return true
    }
    val searchGridState = LazyGridState()
    private val folderListStates = HashMap<String, LazyListState>()
    fun folderListState(path: String): LazyListState = folderListStates.getOrPut(path) { LazyListState() }

    /** Tab File (duyệt mọi file + Gần đây). */
    val files = FilesModel(app, viewModelScope, this)

    var upd by mutableStateOf<UpdState>(UpdState.Idle)
    var updateDialog by mutableStateOf<Release?>(null)

    fun isHidden(p: String) = hidden.any { p == it || p.startsWith("$it/") }
    fun isExcluded(p: String) = Rules.isExcluded(p, userRules, sysOff, autoExclude)

    val visible: List<Folder> by derivedStateOf {
        folders.values.filter { it.path != EXTERNAL && !isExcluded(it.path) && (showHidden || !isHidden(it.path)) }
            .sortedWith(compareByDescending<Folder> { it.path in pinned }.thenByDescending { it.latest })
    }
    val excluded: List<Folder> by derivedStateOf {
        folders.values.filter { isExcluded(it.path) }.sortedBy { it.path.lowercase() }
    }
    val hiddenCount: Int by derivedStateOf { folders.keys.count { isHidden(it) && !isExcluded(it) } }

    val nodes: List<Node> by derivedStateOf {
        val pre = path.joinToString("/")
        val map = LinkedHashMap<String, MutableList<Folder>>()
        for (f in visible) {
            if (pre.isNotEmpty() && !(f.path == pre || f.path.startsWith("$pre/"))) continue
            val rest = if (pre.isEmpty()) f.path else f.path.removePrefix(pre).trimStart('/')
            if (rest.isEmpty()) continue
            val seg = rest.substringBefore('/')
            val full = if (pre.isEmpty()) seg else "$pre/$seg"
            map.getOrPut(full) { mutableListOf() } += f
        }
        map.map { (full, list) ->
            val own = list.firstOrNull { it.path == full }
            Node(
                path = full,
                name = full.substringAfterLast('/'),
                count = list.sumOf { it.count },
                videoCount = list.sumOf { it.videoCount },
                subCount = list.map { it.path.removePrefix(full).trimStart('/').substringBefore('/') }.filter { it.isNotEmpty() }.toSet().size,
                cover = list.mapNotNull { it.cover }.maxByOrNull { it.date },
                own = own,
            )
        }.sortedWith(compareByDescending<Node> { it.path in pinned }.thenBy { it.name.lowercase() })
    }

    /** Ảnh nằm trực tiếp trong thư mục đang mở ở chế độ Thư mục. */
    val ownHere: Folder? by derivedStateOf {
        val pre = path.joinToString("/")
        if (pre.isEmpty()) null else visible.firstOrNull { it.path == pre }
    }

    val searchResults: List<Folder> by derivedStateOf {
        val q = search?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) visible else visible.filter { it.path.lowercase().contains(q) }
    }

    // ---------------- loading ----------------
    private var loadJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { reload(debounce = true) }
    }
    private var observing = false

    fun onPermission(granted: Boolean) {
        hasPermission = granted
        if (granted) {
            if (!observing) {
                getApplication<Application>().contentResolver.registerContentObserver(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer
                )
                observing = true
            }
            reload()
        }
    }

    fun reload(debounce: Boolean = false) {
        if (!hasPermission) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(800)
            loading = !loadedOnce
            try {
                raw = MediaRepository.load(getApplication())
                rebuildFolders()
            } catch (_: Exception) {
            }
            loading = false
            loadedOnce = true
            openFolder?.let { if (it !in folders) openFolder = null }
            viewer?.let { v -> val f = folders[v.folder]; if (f == null || f.count == 0) viewer = null else if (v.index >= f.count) viewer = v.copy(index = f.count - 1) }
        }
    }

    // ---------------- chuyến đi ----------------
    private var raw: Map<String, Folder> = emptyMap()
    var trips by mutableStateOf(prefs.trips)
        private set
    val activeTrip: com.mondiro.mphoto.data.Trip? get() = trips.firstOrNull { it.active }

    /** Ghép album chuyến đi (ảnh/video chụp bằng máy trong khoảng thời gian chuyến đi) vào danh sách thư mục. */
    private fun rebuildFolders() {
        val all = raw.toMutableMap()
        externalFolder?.let { all[it.path] = it }
        if (trips.isNotEmpty()) {
            val cam = raw.values.filter { it.path.startsWith("DCIM", true) && !it.path.contains("screen", true) }.flatMap { it.items }
            for (t in trips) {
                // Ảnh chụp trong thời gian chuyến đi + ảnh đã chuyển sang thư mục riêng của chuyến đi
                val exported = raw["Pictures/${t.name}"]?.items.orEmpty()
                val items = (cam.filter { it.date >= t.start && (t.end == 0L || it.date <= t.end) } + exported)
                    .distinctBy { it.id }.sortedByDescending { it.date }
                all[t.key] = Folder(t.key, items)
            }
        }
        folders = all
    }

    private fun saveTrips(v: List<com.mondiro.mphoto.data.Trip>) { trips = v; prefs.trips = v; rebuildFolders() }

    fun startTrip(rawName: String) {
        var name = rawName.trim().replace('/', '-').ifEmpty { "Chuyến đi" }
        var i = 2
        val base = name
        while (trips.any { it.name == name }) name = "$base ($i)".also { i++ }
        val ended = trips.map { if (it.active) it.copy(end = System.currentTimeMillis()) else it }
        val t = com.mondiro.mphoto.data.Trip(name, System.currentTimeMillis())
        saveTrips(ended + t)
        pinned = pinned + t.key; prefs.pinned = pinned
        toast("Bắt đầu chuyến đi \"$name\" · ảnh chụp từ giờ sẽ vào album này")
    }

    fun endTrip() {
        val a = activeTrip ?: return
        saveTrips(trips.map { if (it.active) it.copy(end = System.currentTimeMillis()) else it })
        toast("Đã kết thúc chuyến đi \"${a.name}\" · ${folders[a.key]?.count ?: 0} ảnh/video")
    }

    fun tripOf(path: String) = trips.firstOrNull { it.key == path }

    fun deleteTrip(path: String) {
        val t = tripOf(path) ?: return
        saveTrips(trips - t)
        pinned = pinned - t.key; prefs.pinned = pinned
        if (openFolder == t.key) openFolder = null
        toast("Đã xoá album chuyến đi (ảnh vẫn giữ nguyên)")
    }

    fun exportTrip(path: String, move: Boolean) {
        val t = tripOf(path) ?: return
        val items = folders[t.key]?.items.orEmpty()
        if (items.isEmpty()) { toast("Chuyến đi chưa có ảnh nào"); return }
        picker = PickerReq(items, move, defaultNew = "Pictures/${t.name}")
    }

    // ---------------- di chuyển / đổi tên thư mục ----------------
    /** Thư mục trong thư viện (bộ nhớ trong) dưới dạng file để di chuyển / đổi tên; null nếu không làm được. */
    fun folderEntry(path: String): com.mondiro.mphoto.data.FileEntry? {
        if (path == EXTERNAL || path.startsWith("Thẻ nhớ") || tripOf(path) != null || path == "Bộ nhớ trong") return null
        val f = java.io.File(com.mondiro.mphoto.data.Files.root, path)
        if (!f.isDirectory) return null
        return com.mondiro.mphoto.data.FileEntry(f.absolutePath, f.name, true, 0, f.lastModified(), children = f.list()?.size ?: 0)
    }

    /** Sau khi chuyển / đổi tên thư mục: giữ nguyên ghim, ẩn, sync, loại trừ theo đường dẫn mới. */
    fun remapPaths(old: String, new: String) {
        if (old.isEmpty() || old == new) return
        fun m(p: String) = if (p == old) new else if (p.startsWith("$old/")) new + p.removePrefix(old) else p
        pinned = pinned.map(::m).toSet(); prefs.pinned = pinned
        hidden = hidden.map(::m).toSet(); prefs.hidden = hidden
        gsync = gsync.map(::m).toSet(); prefs.gsync = gsync
        userRules = userRules.map { it.copy(pattern = m(it.pattern)) }; prefs.rules = userRules
        openFolder = openFolder?.let(::m)
        if (path.isNotEmpty()) path = m(path.joinToString("/")).split('/')
    }

    // ---------------- sửa ảnh ----------------
    /** Ảnh đang mở trong màn hình Sửa ảnh. */
    var editing by mutableStateOf<MediaItem?>(null)

    /**
     * Lưu ảnh đã sửa. overwrite = true: ghi đè file gốc; false: lưu thành file mới cùng thư mục,
     * tên cũ thêm số phía sau (vd IMG_01.jpg -> IMG_01 (1).jpg).
     */
    fun saveEdit(item: MediaItem, ops: List<com.mondiro.mphoto.data.EditOp>, overwrite: Boolean) {
        viewModelScope.launch {
            busy = "Đang lưu ảnh…"
            val ctx = getApplication<Application>()
            try {
                val savedName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val src = com.mondiro.mphoto.data.EditRender.decode(ctx, item.uri, 4096)
                    val out = com.mondiro.mphoto.data.EditRender.applyAll(src, ops)
                    try { writeEdited(item, out, overwrite) } finally { if (out !== src) out.recycle(); src.recycle() }
                }
                editing = null
                toast(if (overwrite && savedName == item.name) "Đã lưu đè ảnh gốc" else "Đã lưu thành $savedName")
                reload()
            } catch (e: SecurityException) {
                // Chưa có quyền sửa file gốc: xin quyền của Android rồi lưu lại
                try {
                    val pi = MediaStore.createWriteRequest(ctx.contentResolver, listOf(item.uri))
                    permissionRequest = PermReq(pi.intentSender) { saveEdit(item, ops, overwrite) }
                } catch (_: Exception) { toast("Không có quyền sửa ảnh này") }
            } catch (e: Exception) {
                toast("Không lưu được ảnh: ${e.message}")
            }
            busy = null
        }
    }

    private fun writeEdited(item: MediaItem, bmp: android.graphics.Bitmap, overwriteWanted: Boolean): String {
        val ctx = getApplication<Application>()
        val r = ctx.contentResolver
        val mime = item.mime.lowercase()
        // Định dạng giữ nguyên; định dạng không ghi được (HEIC…) thì lưu thành JPG mới
        val (fmt, ext, outMime) = when (mime) {
            "image/png" -> Triple(android.graphics.Bitmap.CompressFormat.PNG, "png", "image/png")
            "image/webp" -> Triple(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp")
            "image/jpeg", "image/jpg" -> Triple(android.graphics.Bitmap.CompressFormat.JPEG, item.name.substringAfterLast('.', "jpg"), "image/jpeg")
            else -> Triple(android.graphics.Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg")
        }
        val sameFormat = mime == outMime || (mime == "image/jpg" && outMime == "image/jpeg")
        val overwrite = overwriteWanted && sameFormat
        val path = try {
            r.query(item.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
        val base = item.name.substringBeforeLast('.')
        val canFile = path != null && com.mondiro.mphoto.data.Files.hasAllFilesAccess() && java.io.File(path).exists()

        if (overwrite) {
            if (canFile) {
                java.io.File(path!!).outputStream().use { bmp.compress(fmt, 95, it) }
                com.mondiro.mphoto.data.Files.scan(ctx, listOf(path))
            } else {
                r.openOutputStream(item.uri, "wt")!!.use { bmp.compress(fmt, 95, it) }
            }
            return item.name
        }
        // Lưu thành file mới
        if (canFile) {
            val src = java.io.File(path!!)
            val target = com.mondiro.mphoto.data.Files.uniqueTarget(src.parentFile!!, "$base.$ext")
            target.outputStream().use { bmp.compress(fmt, 95, it) }
            com.mondiro.mphoto.data.Files.scan(ctx, listOf(target.absolutePath))
            return target.name
        }
        val names = folders[item.folder]?.items?.map { it.name }?.toSet().orEmpty()
        var n = 1
        var name = "$base ($n).$ext"
        while (name in names) { n++; name = "$base ($n).$ext" }
        val rel = if (com.mondiro.mphoto.data.FileOps.canPlace(item.folder, false)) com.mondiro.mphoto.data.FileOps.normalize(item.folder) else "Pictures"
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, outMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$rel/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (rel.startsWith("Download")) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = r.insert(coll, values) ?: throw IllegalStateException("không tạo được file mới")
        r.openOutputStream(uri)!!.use { bmp.compress(fmt, 95, it) }
        r.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return name
    }

    // ---------------- mở từ app khác ----------------
    /** Ảnh/video mở từ app khác mà thư viện không có (vd file của app khác chia sẻ). */
    private var externalFolder: Folder? = null
    /** Đang xem ảnh mở từ app khác: đóng trình xem là quay lại app đó. */
    var externalViewing by mutableStateOf(false)

    fun openUri(uri: android.net.Uri, type: String?) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // 1. Ảnh trong thư viện (vd ảnh chụp màn hình vừa lưu): mở đúng thư mục để vuốt qua ảnh khác được
            val id = if (uri.authority == MediaStore.AUTHORITY) try { android.content.ContentUris.parseId(uri) } catch (_: Exception) { -1L } else -1L
            if (id > 0 && hasPermission) {
                try { raw = MediaRepository.load(ctx); rebuildFolders() } catch (_: Exception) {}
                for (f in folders.values) {
                    if (f.path.startsWith("Chuyến đi/")) continue
                    val i = f.items.indexOfFirst { it.id == id }
                    if (i >= 0) { viewer = ViewerState(f.path, i); externalViewing = true; return@launch }
                }
            }
            // 2. Không có trong thư viện: xem riêng file đó
            val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var name = uri.lastPathSegment ?: "file"
                var size = 0L
                try {
                    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = c.getLong(1) }
                    }
                } catch (_: Exception) {}
                val mime = type ?: ctx.contentResolver.getType(uri) ?: "image/*"
                MediaItem(
                    id = uri.hashCode().toLong(), uri = uri, folder = EXTERNAL, name = name, date = System.currentTimeMillis(),
                    isVideo = mime.startsWith("video"), duration = 0, mime = mime, size = size, width = 0, height = 0,
                )
            }
            externalFolder = Folder(EXTERNAL, listOf(item))
            rebuildFolders()
            viewer = ViewerState(EXTERNAL, 0)
            externalViewing = true
        }
    }

    override fun onCleared() {
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    // ---------------- actions ----------------
    fun toast(t: String) { toast = ToastMsg(t) }

    fun selectTab(t: Tab) {
        clearSelection()
        if (tab == t && t == Tab.Folder) path = emptyList()
        tab = t; openFolder = null; search = null
        if (t == Tab.Files) files.refresh()
    }

    fun togglePin(p: String) {
        pinned = if (p in pinned) pinned - p else pinned + p
        prefs.pinned = pinned
        toast(if (p in pinned) "Đã ghim lên đầu" else "Đã bỏ ghim")
    }

    fun toggleHide(p: String) {
        val nowHidden = p !in hidden
        hidden = if (nowHidden) hidden + p else hidden - p
        prefs.hidden = hidden
        if (nowHidden && !showHidden) {
            if (openFolder == p) openFolder = null
            toast("Đã ẩn · Vào Cài đặt › Hiện thư mục ẩn để xem lại")
        } else toast(if (nowHidden) "Đã ẩn thư mục" else "Đã bỏ ẩn")
    }

    fun setShowHiddenAuthed(v: Boolean) {
        showHidden = v
        toast(if (v) "Đang hiện thư mục ẩn" else "Đã ẩn lại thư mục ẩn")
    }

    fun excludeFolder(p: String) {
        userRules = userRules.filterNot { it.pattern == p } + UserRule(p, false)
        prefs.rules = userRules
        if (openFolder == p) openFolder = null
        toast("Đã loại trừ · xem lại trong Cài đặt")
    }

    fun includeFolder(p: String) {
        userRules = userRules.filterNot { it.pattern == p } + UserRule(p, true)
        prefs.rules = userRules
        toast("Đã đưa \"${p.substringAfterLast('/')}\" trở lại thư viện")
    }

    fun addRule(pattern: String, include: Boolean) {
        val p = pattern.trim().trim('/')
        if (p.isEmpty()) return
        userRules = userRules.filterNot { it.pattern.equals(p, true) } + UserRule(p, include)
        prefs.rules = userRules
    }

    fun toggleRule(i: Int) {
        userRules = userRules.mapIndexed { idx, r -> if (idx == i) r.copy(include = !r.include) else r }
        prefs.rules = userRules
    }

    fun deleteRule(i: Int) {
        userRules = userRules.filterIndexed { idx, _ -> idx != i }
        prefs.rules = userRules
    }

    fun toggleSys(id: String) {
        sysOff = if (id in sysOff) sysOff - id else sysOff + id
        prefs.sysOff = sysOff
    }

    fun toggleAuto() { autoExclude = !autoExclude; prefs.autoExclude = autoExclude }

    fun setDefault(t: String) { defaultTab = t; prefs.defaultTab = t }

    fun setSync(p: String, on: Boolean) {
        gsync = if (on) gsync + p else gsync - p
        prefs.gsync = gsync
    }

    // ---------------- update ----------------
    fun checkUpdate(manual: Boolean) {
        if (upd is UpdState.Checking || upd is UpdState.Downloading) return
        upd = UpdState.Checking
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = try {
                val r = Updater.fetchLatest()
                if (Updater.isNewer(r.version)) UpdState.Available(r) else UpdState.Latest
            } catch (e: Exception) {
                UpdState.Error
            }
            val wait = 700 - (System.currentTimeMillis() - started)
            if (manual && wait > 0) delay(wait)
            upd = result
            when (result) {
                is UpdState.Available -> updateDialog = result.release
                UpdState.Latest -> if (manual) toast("Đã là bản mới nhất")
                else -> if (manual) toast("Không kiểm tra được cập nhật, thử lại sau")
            }
        }
    }

    /** File APK đã tải xong nhưng chờ người dùng cấp quyền "cài ứng dụng". */
    private var pendingApk: java.io.File? = null

    fun downloadAndInstall(r: Release) {
        val url = r.apkUrl ?: run { toast("Bản này chưa có file cài"); return }
        if (upd is UpdState.Downloading) return
        updateDialog = null
        viewModelScope.launch {
            upd = UpdState.Downloading(0f)
            val f = try {
                Updater.download(getApplication(), r) { p -> upd = p }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                upd = UpdState.Available(r)
                updateErrorText = "Lỗi tải: " + (e.message ?: Updater.describe(e))
                updateError = r
                return@launch
            }
            upd = UpdState.Available(r)
            installApk(f)
        }
    }

    private fun installApk(f: java.io.File) {
        try {
            if (Updater.install(getApplication(), f)) {
                pendingApk = null
                // 6 giây không thấy Android phản hồi -> mở trình cài kiểu cũ
                viewModelScope.launch {
                    delay(6000)
                    if (!com.mondiro.mphoto.update.InstallEvents.gotCallback) {
                        try { Updater.installViaView(getApplication(), f) } catch (e: Exception) {
                            updateErrorText = "Lỗi cài: " + Updater.describe(e)
                            (upd as? UpdState.Available)?.let { updateError = it.release }
                        }
                    }
                }
            } else { pendingApk = f; toast("Bật \"Cho phép từ nguồn này\" cho M Photo rồi quay lại") }
        } catch (e: Exception) {
            pendingApk = null
            updateErrorText = "Lỗi cài: " + Updater.describe(e)
            (upd as? UpdState.Available)?.let { updateError = it.release }
        }
    }

    /** Gọi khi app quay lại màn hình: nếu vừa cấp quyền cài app thì cài luôn. */
    fun onResume() {
        files.checkPermission()
        if (tab == Tab.Files) files.refresh()
        val f = pendingApk ?: return
        if (getApplication<Application>().packageManager.canRequestPackageInstalls() && f.exists()) installApk(f)
    }

    /** Lỗi tải: hiện hộp thoại cho tải bằng trình duyệt. */
    var updateError by mutableStateOf<Release?>(null)
    var updateErrorText by mutableStateOf("")

    // ---------------- chọn nhiều + copy / di chuyển / xoá ----------------
    var selection by mutableStateOf<Set<Long>>(emptySet())
    var selectionFolder by mutableStateOf<String?>(null)
    var picker by mutableStateOf<PickerReq?>(null)
    var permissionRequest by mutableStateOf<PermReq?>(null)
    var busy by mutableStateOf<String?>(null)

    /** Ảnh chọn gần nhất: mốc để giữ lâu ảnh khác chọn cả dãy. */
    private var selectionAnchor: Long? = null

    fun toggleSelect(folder: String, id: Long) {
        if (selectionFolder != folder) { selection = emptySet(); selectionFolder = folder }
        selection = if (id in selection) selection - id else selection + id
        selectionAnchor = if (id in selection) id else selection.lastOrNull()
    }

    /**
     * Giữ lâu khi đang chọn: chọn tất cả ảnh từ ảnh chọn gần nhất (A) tới ảnh vừa giữ (B).
     * Chưa chọn gì thì giữ lâu = bắt đầu chọn như cũ.
     */
    fun selectRangeTo(folder: String, id: Long) {
        val f = folders[folder]
        val anchor = selectionAnchor
        if (f == null || selection.isEmpty() || selectionFolder != folder || anchor == null) { toggleSelect(folder, id); return }
        val a = f.items.indexOfFirst { it.id == anchor }
        val b = f.items.indexOfFirst { it.id == id }
        if (a < 0 || b < 0 || a == b) { toggleSelect(folder, id); return }
        val before = selection.size
        selection = selection + f.items.subList(min(a, b), max(a, b) + 1).map { it.id }
        selectionAnchor = id
        toast("Đã chọn thêm ${selection.size - before} mục · tổng ${selection.size}")
    }
    fun selectAll(f: Folder) {
        selectionFolder = f.path
        selection = if (selection.size == f.count) emptySet() else f.items.map { it.id }.toSet()
    }
    fun clearSelection() { selection = emptySet(); selectionFolder = null; selectionAnchor = null }
    fun selectedItems(): List<MediaItem> = folders[selectionFolder]?.items?.filter { it.id in selection }.orEmpty()

    fun requestDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        try {
            val pi = MediaStore.createTrashRequest(getApplication<Application>().contentResolver, items.map { it.uri }, true)
            permissionRequest = PermReq(pi.intentSender) {
                toast("Đã chuyển ${items.size} mục vào thùng rác")
                clearSelection(); reload()
            }
        } catch (e: Exception) { toast("Không xoá được") }
    }

    fun startCopyMove(items: List<MediaItem>, move: Boolean) {
        if (items.isNotEmpty()) picker = PickerReq(items, move)
    }

    fun performCopyMove(req: PickerReq, destRaw: String) {
        picker = null
        // Có quyền truy cập tất cả file: chép / chuyển tới bất kỳ thư mục nào trong Bộ nhớ trong
        val allFiles = com.mondiro.mphoto.data.Files.hasAllFilesAccess()
        val dest = if (allFiles) destRaw.trim().trim('/') else com.mondiro.mphoto.data.FileOps.normalize(destRaw)
        val bad = if (allFiles) emptyList() else req.items.filter { !com.mondiro.mphoto.data.FileOps.canPlace(dest, it.isVideo) }
        if ((!allFiles && dest.isEmpty()) || bad.isNotEmpty()) {
            toast(if (bad.any { it.isVideo }) "Video chỉ chép được vào DCIM, Movies, Pictures, Download" else "Ảnh chỉ chép được vào DCIM, Pictures, Download")
            return
        }
        val items = req.items.filter { it.folder != dest.ifEmpty { "Bộ nhớ trong" } }
        if (items.isEmpty()) { toast("Đã ở sẵn trong thư mục này"); return }
        val run: () -> Unit = {
            viewModelScope.launch {
                var ok = 0; var fail = 0
                items.forEachIndexed { i, it ->
                    busy = (if (req.move) "Đang di chuyển " else "Đang chép ") + "${i + 1}/${items.size}…"
                    try {
                        if (req.move) com.mondiro.mphoto.data.FileOps.move(getApplication(), it, dest)
                        else com.mondiro.mphoto.data.FileOps.copy(getApplication(), it, dest)
                        ok++
                    } catch (e: Exception) { fail++ }
                }
                busy = null
                clearSelection()
                reload()
                val verb = if (req.move) "di chuyển" else "chép"
                toast(if (fail == 0) "Đã $verb $ok mục vào ${dest.substringAfterLast('/').ifEmpty { "Bộ nhớ trong" }}" else "Đã $verb $ok mục, lỗi $fail mục")
            }
        }
        if (req.move && !allFiles) {
            try {
                val pi = MediaStore.createWriteRequest(getApplication<Application>().contentResolver, items.map { it.uri })
                permissionRequest = PermReq(pi.intentSender, run)
            } catch (e: Exception) { toast("Không di chuyển được") }
        } else run()
    }
}

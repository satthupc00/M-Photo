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

enum class Tab { Album, Folder, Settings }

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
data class PickerReq(val items: List<MediaItem>, val move: Boolean)
class PermReq(val sender: android.content.IntentSender, val onGranted: () -> Unit)
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
    val searchGridState = LazyGridState()
    private val folderListStates = HashMap<String, LazyListState>()
    fun folderListState(path: String): LazyListState = folderListStates.getOrPut(path) { LazyListState() }

    var upd by mutableStateOf<UpdState>(UpdState.Idle)
    var updateDialog by mutableStateOf<Release?>(null)

    fun isHidden(p: String) = hidden.any { p == it || p.startsWith("$it/") }
    fun isExcluded(p: String) = Rules.isExcluded(p, userRules, sysOff, autoExclude)

    val visible: List<Folder> by derivedStateOf {
        folders.values.filter { !isExcluded(it.path) && (showHidden || !isHidden(it.path)) }
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
                folders = MediaRepository.load(getApplication())
            } catch (_: Exception) {
            }
            loading = false
            loadedOnce = true
            openFolder?.let { if (it !in folders) openFolder = null }
            viewer?.let { v -> val f = folders[v.folder]; if (f == null || f.count == 0) viewer = null else if (v.index >= f.count) viewer = v.copy(index = f.count - 1) }
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
                Updater.download(getApplication(), url, r.version, r.apkSize) { p -> upd = UpdState.Downloading(p) }
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

    fun toggleSelect(folder: String, id: Long) {
        if (selectionFolder != folder) { selection = emptySet(); selectionFolder = folder }
        selection = if (id in selection) selection - id else selection + id
    }
    fun selectAll(f: Folder) {
        selectionFolder = f.path
        selection = if (selection.size == f.count) emptySet() else f.items.map { it.id }.toSet()
    }
    fun clearSelection() { selection = emptySet(); selectionFolder = null }
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
        val dest = com.mondiro.mphoto.data.FileOps.normalize(destRaw)
        val bad = req.items.filter { !com.mondiro.mphoto.data.FileOps.canPlace(dest, it.isVideo) }
        if (dest.isEmpty() || bad.isNotEmpty()) {
            toast(if (bad.any { it.isVideo }) "Video chỉ chép được vào DCIM, Movies, Pictures, Download" else "Ảnh chỉ chép được vào DCIM, Pictures, Download")
            return
        }
        val items = req.items.filter { it.folder != dest }
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
                toast(if (fail == 0) "Đã $verb $ok mục vào ${dest.substringAfterLast('/')}" else "Đã $verb $ok mục, lỗi $fail mục")
            }
        }
        if (req.move) {
            try {
                val pi = MediaStore.createWriteRequest(getApplication<Application>().contentResolver, items.map { it.uri })
                permissionRequest = PermReq(pi.intentSender, run)
            } catch (e: Exception) { toast("Không di chuyển được") }
        } else run()
    }
}

package com.mondiro.mphoto.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mondiro.mphoto.data.FileEntry
import com.mondiro.mphoto.data.FileKind
import com.mondiro.mphoto.data.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

enum class FileSort(val label: String) { Name("Tên A→Z"), Date("Mới nhất"), Size("Dung lượng") }

/** Trạng thái tab File (Gần đây + Bộ nhớ). */
class FilesModel(private val app: Application, private val scope: CoroutineScope, private val vm: AppViewModel) {
    var granted by mutableStateOf(Files.hasAllFilesAccess())
        private set
    var segment by mutableStateOf("recent")
    var chip by mutableStateOf("Tất cả")
    var cwd by mutableStateOf(Files.root)
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var recent by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var sort by mutableStateOf(FileSort.Name)
    var showHidden by mutableStateOf(false)
    var storage by mutableStateOf(0L to 0L)
        private set
    /** File đang mở menu thao tác. */
    var sheet by mutableStateOf<FileEntry?>(null)
    /** Hộp thoại: "rename", "delete", "info", "copy", "move". */
    var dialog by mutableStateOf<String?>(null)
    var dialogTarget by mutableStateOf<FileEntry?>(null)

    private val listStates = HashMap<String, LazyListState>()
    fun listState(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    val relCwd: String get() = Files.rel(cwd.absolutePath)
    val atRoot: Boolean get() = cwd.absolutePath == Files.root.absolutePath

    val sorted: List<FileEntry> by derivedStateOf {
        val q = vm.search?.trim()?.lowercase().orEmpty()
        val base = if (q.isEmpty()) entries else entries.filter { it.name.lowercase().contains(q) }
        val cmp = when (sort) {
            FileSort.Name -> compareBy<FileEntry> { it.name.lowercase() }
            FileSort.Date -> compareByDescending { it.modified }
            FileSort.Size -> compareByDescending<FileEntry> { it.size }.thenBy { it.name.lowercase() }
        }
        base.filter { it.isDir }.sortedWith(if (sort == FileSort.Size) compareBy<FileEntry> { it.name.lowercase() } else cmp) +
            base.filter { !it.isDir }.sortedWith(cmp)
    }

    val recentFiltered: List<FileEntry> by derivedStateOf {
        val q = vm.search?.trim()?.lowercase().orEmpty()
        recent.filter { e ->
            (q.isEmpty() || e.name.lowercase().contains(q)) && when (chip) {
                "Tất cả" -> true
                "Tải về" -> Files.rel(e.path).startsWith("Download/", ignoreCase = true)
                else -> e.kind.chip == chip
            }
        }
    }

    fun checkPermission() {
        val g = Files.hasAllFilesAccess()
        if (g != granted) { granted = g; if (g) refresh() }
    }

    private var job: Job? = null
    fun refresh() {
        granted = Files.hasAllFilesAccess()
        if (!granted) return
        job?.cancel()
        job = scope.launch {
            loading = entries.isEmpty() && recent.isEmpty()
            try {
                storage = Files.storage()
                entries = Files.list(cwd, showHidden)
                recent = Files.recent(app, 30) { rel -> vm.isExcluded(rel) }
            } catch (_: Exception) {
            }
            loading = false
        }
    }

    fun open(dir: File) {
        cwd = dir
        entries = emptyList()
        scope.launch { try { entries = Files.list(dir, showHidden) } catch (_: Exception) {} }
    }

    fun up(): Boolean {
        if (atRoot) return false
        open(cwd.parentFile ?: Files.root)
        return true
    }

    fun toggleHidden() { showHidden = !showHidden; open(cwd) }

    // ---------------- thao tác ----------------
    private fun afterChange(paths: List<String>) {
        Files.scan(app, paths)
        refresh()
        vm.reload(debounce = true)
    }

    fun rename(e: FileEntry, newName: String) = scope.launch {
        try {
            val t = Files.rename(e.file, newName)
            vm.toast("Đã đổi tên")
            afterChange(listOf(e.path, t.absolutePath))
        } catch (ex: Exception) { vm.toast("Không đổi tên được: ${ex.message}") }
    }

    fun delete(e: FileEntry) = scope.launch {
        val ok = Files.delete(e.file)
        vm.toast(if (ok) "Đã xoá ${e.name}" else "Không xoá được ${e.name}")
        afterChange(listOf(e.path))
    }

    fun copyMove(e: FileEntry, dest: File, move: Boolean) = scope.launch {
        vm.busy = if (move) "Đang di chuyển…" else "Đang chép…"
        try {
            val t = if (move) Files.move(e.file, dest) else Files.copy(e.file, dest)
            vm.toast((if (move) "Đã di chuyển" else "Đã chép") + " vào " + dest.name.ifEmpty { "Bộ nhớ trong" })
            afterChange(listOfNotNull(if (move) e.path else null, t.absolutePath))
        } catch (ex: Exception) {
            vm.toast((if (move) "Không di chuyển được: " else "Không chép được: ") + ex.message)
        }
        vm.busy = null
    }

    fun newFolder(parent: File, name: String): File? {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) return null
        val f = File(parent, n)
        return if (f.exists() || f.mkdirs()) f else null
    }

    companion object {
        val CHIPS = listOf("Tất cả", "Tải về", FileKind.Image.chip, FileKind.Video.chip, FileKind.Doc.chip, FileKind.Design.chip, FileKind.Apk.chip, FileKind.Archive.chip, FileKind.Audio.chip)
    }
}

package com.mondiro.mphoto.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.mondiro.mphoto.data.FileEntry
import com.mondiro.mphoto.data.FileKind
import com.mondiro.mphoto.data.Files
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ Màn hình chính tab File

@Composable
fun FilesScreen(vm: AppViewModel, bottomPad: Dp) {
    val fm = vm.files
    LaunchedEffect(Unit) { fm.refresh() }
    if (!fm.granted) { AllFilesPermission(); return }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(Mc.panel).padding(3.dp),
        ) {
            Seg("Gần đây", fm.segment == "recent", Modifier.weight(1f)) { fm.segment = "recent" }
            Seg("Bộ nhớ", fm.segment == "browse", Modifier.weight(1f)) { fm.segment = "browse" }
        }
        if (fm.segment == "recent") RecentList(vm, bottomPad) else BrowseList(vm, bottomPad)
    }
}

@Composable
private fun Seg(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = if (on) Color.White else Mc.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(if (on) Mc.selected else Color.Transparent)
            .pressable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
private fun RecentList(vm: AppViewModel, bottomPad: Dp) {
    val fm = vm.files
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilesModel.CHIPS.forEach { c -> Chip(c, fm.chip == c, Mc.redSoft) { fm.chip = c } }
    }
    val list = fm.recentFiltered
    if (list.isEmpty()) {
        EmptyState(if (fm.loading) "Đang tải…" else "Không có file", "File mới trong 30 ngày sẽ hiện ở đây")
        return
    }
    val groups = remember(list) { list.groupBy { dayGroup(it.added) } }
    LazyColumn(
        state = fm.listState("recent"),
        flingBehavior = rememberFastFling(vm.scrollSpeed),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomPad),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (label, group) ->
            item(key = "h:$label") { SectionTitle("$label · ${group.size}") }
            items(group, key = { "r:" + it.path }) { e -> FileRow(vm, e, recent = true, modifier = Modifier.animateItem()) }
        }
    }
}

@Composable
private fun BrowseList(vm: AppViewModel, bottomPad: Dp) {
    val fm = vm.files
    if (fm.atRoot) {
        val (used, total) = fm.storage
        if (total > 0) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mc.panel).padding(14.dp)) {
            Row {
                Text("Bộ nhớ trong", color = Mc.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${fmtSize(used)} / ${fmtSize(total)}", color = Mc.muted, fontSize = 12.sp)
            }
            LinearProgressIndicator(progress = { used.toFloat() / total }, color = Mc.red, trackColor = Color(0xFF474C55), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
    }
    // Đường dẫn
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val parts = fm.relCwd.split('/').filter { it.isNotEmpty() }
        FileCrumb("Bộ nhớ trong", parts.isEmpty()) { fm.open(Files.root) }
        parts.forEachIndexed { i, seg ->
            Text("›", color = Color(0xFF555A64), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 2.dp))
            FileCrumb(seg, i == parts.lastIndex) { fm.open(File(Files.root, parts.take(i + 1).joinToString("/"))) }
        }
    }
    // Sắp xếp + file ẩn
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Sort, null, tint = Mc.muted, modifier = Modifier.size(18.dp))
        FileSort.entries.forEach { s -> Chip(s.label, fm.sort == s, Mc.redSoft) { fm.sort = s } }
        Chip(if (fm.showHidden) "File ẩn: Hiện" else "File ẩn: Tắt", fm.showHidden, Mc.green) { fm.toggleHidden() }
    }
    val list = fm.sorted
    if (list.isEmpty()) { EmptyState("Thư mục trống", if (vm.search.isNullOrBlank()) "Không có file nào ở đây" else "Không tìm thấy"); return }
    LazyColumn(
        state = fm.listState("b:" + fm.relCwd),
        flingBehavior = rememberFastFling(vm.scrollSpeed),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomPad),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list, key = { it.path }) { e -> FileRow(vm, e, recent = false, modifier = Modifier.animateItem()) }
    }
}

@Composable
private fun FileCrumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (current) Color.White else Mc.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (current) Mc.panel else Color.Transparent)
            .pressable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ 1 dòng file

@Composable
fun FileRow(vm: AppViewModel, e: FileEntry, recent: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val isNew = recent && System.currentTimeMillis() - e.added < 24 * 3600_000L
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .pressable(onLongClick = { vm.files.sheet = e }) { onFileClick(ctx, vm, e) }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(e, Modifier.size(46.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = Mc.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isNew) Text(
                    "MỚI", color = Mc.green, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(Mc.green.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            val sub = when {
                e.isDir -> "${e.children} mục · ${fmtWhen(e.modified)}"
                recent -> "${fmtSize(e.size)} · ${fmtWhen(e.added)} · /" + Files.rel(e.path).substringBeforeLast('/', "")
                else -> "${fmtSize(e.size)} · ${fmtWhen(e.modified)}"
            }
            Text(sub, color = Mc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.isDir) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF5D626C))
        else IconBtn(Icons.Outlined.MoreVert, "Tuỳ chọn", tint = Mc.muted, modifier = Modifier.size(38.dp)) { vm.files.sheet = e }
    }
}

@Composable
fun FileIcon(e: FileEntry, modifier: Modifier) {
    val k = e.kind
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(k.color), contentAlignment = Alignment.Center) {
        when (k) {
            FileKind.Folder -> Icon(Icons.Outlined.Folder, null, tint = Mc.star, modifier = Modifier.size(24.dp))
            FileKind.Image, FileKind.Video -> AsyncImage(model = e.file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Text(e.ext.uppercase().take(4).ifEmpty { "FILE" }, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

private fun onFileClick(ctx: Context, vm: AppViewModel, e: FileEntry) {
    if (e.isDir) { vm.files.open(e.file); return }
    // File nén: mở menu để chọn Giải nén
    if (e.kind == FileKind.Archive) { vm.files.sheet = e; return }
    if (e.kind == FileKind.Image || e.kind == FileKind.Video) {
        // Ảnh / video: mở bằng trình xem của M Photo nếu thư viện có file này
        val rel = Files.rel(e.path)
        val dir = rel.substringBeforeLast('/', "").ifEmpty { "Bộ nhớ trong" }
        val f = vm.folders[dir]
        val idx = f?.items?.indexOfFirst { it.name == e.name } ?: -1
        if (f != null && idx >= 0) { vm.viewer = ViewerState(dir, idx); return }
    }
    openExternal(ctx, vm, e)
}

fun fileUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

fun openExternal(ctx: Context, vm: AppViewModel, e: FileEntry) {
    try {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(ctx, e.file), e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Mở bằng"))
    } catch (_: Exception) { vm.toast("Không có app nào mở được file này") }
}

private fun dayStart(offsetDays: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, -offsetDays)
}.timeInMillis

private fun dayGroup(t: Long): String = when {
    t >= dayStart(0) -> "Hôm nay"
    t >= dayStart(1) -> "Hôm qua"
    t >= dayStart(7) -> "7 ngày qua"
    else -> "30 ngày qua"
}

private fun fmtWhen(t: Long): String =
    if (t >= dayStart(0)) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(t))

// ------------------------------------------------------------------ Xin quyền

@Composable
private fun AllFilesPermission() {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, tint = Mc.red, modifier = Modifier.size(56.dp))
            Text("Cho phép truy cập tất cả file", color = Mc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Text("Để xem, mở, đổi tên, copy, di chuyển mọi loại file (PDF, ZIP, APK, PSD…). Bật \"Cho phép quản lý tất cả file\" cho M Photo trong trang vừa mở rồi quay lại.", color = Mc.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Text(
                "Mở cài đặt", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Mc.red).pressable {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Menu thao tác + hộp thoại

@Composable
fun FilesOverlays(vm: AppViewModel, wide: Boolean) {
    val fm = vm.files
    val ctx = LocalContext.current
    fm.sheet?.let { e ->
        BackHandler { fm.sheet = null }
        val shown = remember(e.path) { MutableTransitionState(false).apply { targetState = true } }
        Box(
            Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(remember { MutableInteractionSource() }, null) { fm.sheet = null },
            contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
        ) {
            AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn()) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth()
                        .clip(if (wide) RoundedCornerShape(22.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(Mc.panel).clickable(remember { MutableInteractionSource() }, null) {}
                        .navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileIcon(e, Modifier.size(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(e.name, color = Mc.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("/" + Files.rel(e.path), color = Mc.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(1.dp).background(Mc.line))
                    val close = { fm.sheet = null }
                    if (e.isDir) SheetAction(Icons.Outlined.FolderOpen, "Mở thư mục") { close(); fm.segment = "browse"; fm.open(e.file) }
                    else {
                        SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "Mở bằng…") { close(); openExternal(ctx, vm, e) }
                        SheetAction(Icons.Outlined.Share, "Chia sẻ") {
                            close()
                            try {
                                val i = Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, fileUri(ctx, e.file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(Intent.createChooser(i, "Chia sẻ"))
                            } catch (_: Exception) { vm.toast("Không chia sẻ được") }
                        }
                        if (fm.segment == "recent") SheetAction(Icons.Outlined.Folder, "Mở thư mục chứa file") {
                            close(); fm.segment = "browse"; fm.open(e.file.parentFile ?: Files.root)
                        }
                    }
                    if (e.kind == FileKind.Archive) SheetAction(Icons.Outlined.Unarchive, "Giải nén") {
                        close()
                        if (e.ext == "zip") fm.unzip(e) else vm.toast("Hiện chỉ giải nén được file ZIP, chưa hỗ trợ ${e.ext.uppercase()}")
                    }
                    SheetAction(Icons.Outlined.ContentCopy, "Copy tới…") { close(); fm.dialogTarget = e; fm.dialog = "copy" }
                    SheetAction(Icons.AutoMirrored.Outlined.DriveFileMove, "Di chuyển tới…") { close(); fm.dialogTarget = e; fm.dialog = "move" }
                    SheetAction(Icons.Outlined.DriveFileRenameOutline, "Đổi tên") { close(); fm.dialogTarget = e; fm.dialog = "rename" }
                    SheetAction(Icons.Outlined.Info, "Thông tin") { close(); fm.dialogTarget = e; fm.dialog = "info" }
                    SheetAction(Icons.Outlined.DeleteOutline, "Xoá", tint = Mc.redSoft) { close(); fm.dialogTarget = e; fm.dialog = "delete" }
                }
            }
        }
    }

    val e = fm.dialogTarget
    if (e != null) when (fm.dialog) {
        "rename" -> RenameDialog(vm, e)
        "delete" -> AlertDialog(
            onDismissRequest = { fm.dialog = null },
            containerColor = Mc.panel,
            title = { Text("Xoá ${if (e.isDir) "thư mục" else "file"}?", fontWeight = FontWeight.Bold) },
            text = { Text("\"${e.name}\" sẽ bị xoá vĩnh viễn" + (if (e.isDir) " cùng toàn bộ nội dung bên trong." else ".") + " Không khôi phục được.", color = Mc.text) },
            confirmButton = { TextButton({ fm.dialog = null; fm.delete(e) }) { Text("Xoá", color = Mc.red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ fm.dialog = null }) { Text("Huỷ", color = Mc.muted) } },
        )
        "info" -> AlertDialog(
            onDismissRequest = { fm.dialog = null },
            containerColor = Mc.panel,
            title = { Text("Thông tin", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Info("Tên", e.name)
                    Info("Đường dẫn", "/" + Files.rel(e.path))
                    Info("Loại", if (e.isDir) "Thư mục · ${e.children} mục" else e.kind.chip + " · " + e.ext.uppercase())
                    if (!e.isDir) Info("Dung lượng", fmtSize(e.size))
                    Info("Sửa lần cuối", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(e.modified)))
                }
            },
            confirmButton = { TextButton({ fm.dialog = null }) { Text("Đóng", color = Mc.red) } },
        )
        "copy", "move" -> DestPicker(vm, e, move = fm.dialog == "move")
    }
}

@Composable
private fun Info(k: String, v: String) {
    Column {
        Text(k, color = Mc.muted, fontSize = 12.sp)
        Text(v, color = Mc.text, fontSize = 14.sp)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, tint: Color = Mc.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun RenameDialog(vm: AppViewModel, e: FileEntry) {
    val fm = vm.files
    val baseLen = if (!e.isDir && e.name.contains('.') && !e.name.startsWith('.')) e.name.lastIndexOf('.') else e.name.length
    var v by remember(e.path) { mutableStateOf(TextFieldValue(e.name, TextRange(0, baseLen))) }
    AlertDialog(
        onDismissRequest = { fm.dialog = null },
        containerColor = Mc.panel,
        title = { Text("Đổi tên", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { v = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = Mc.text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mc.red, unfocusedBorderColor = Mc.line, cursorColor = Mc.red),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton({ fm.dialog = null; fm.rename(e, v.text) }, enabled = v.text.isNotBlank() && v.text != e.name) { Text("Đổi tên", color = Mc.red, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton({ fm.dialog = null }) { Text("Huỷ", color = Mc.muted) } },
    )
}

/** Chọn thư mục đích để copy / di chuyển: duyệt từng cấp, tạo được thư mục mới. */
@Composable
private fun DestPicker(vm: AppViewModel, e: FileEntry, move: Boolean) {
    val fm = vm.files
    var dir by remember(e.path) { mutableStateOf(if (fm.segment == "browse") fm.cwd else Files.root) }
    var newName by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val subdirs by produceState(emptyList<FileEntry>(), dir, reload) {
        value = try { Files.list(dir, false).filter { it.isDir }.sortedBy { it.name.lowercase() } } catch (_: Exception) { emptyList() }
    }
    val atRoot = dir.absolutePath == Files.root.absolutePath
    val verb = if (move) "Di chuyển" else "Copy"
    AlertDialog(
        onDismissRequest = { fm.dialog = null },
        containerColor = Mc.panel,
        title = { Text("$verb \"${e.name}\" tới…", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!atRoot) IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Lên 1 cấp", modifier = Modifier.size(38.dp)) { dir = dir.parentFile ?: Files.root }
                    Text("/" + Files.rel(dir.absolutePath).ifEmpty { "Bộ nhớ trong" }, color = Mc.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (newName == null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { newName = "" }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, tint = Mc.red)
                    Spacer(Modifier.width(12.dp))
                    Text("Thư mục mới…", color = Mc.red, fontWeight = FontWeight.Bold)
                } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    OutlinedTextField(
                        value = newName!!, onValueChange = { newName = it }, singleLine = true, placeholder = { Text("Tên thư mục", color = Mc.muted) },
                        textStyle = TextStyle(fontSize = 14.sp, color = Mc.text),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mc.red, unfocusedBorderColor = Mc.line, cursorColor = Mc.red),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton({
                        val f = fm.newFolder(dir, newName!!)
                        if (f != null) { dir = f; newName = null; reload++ } else vm.toast("Không tạo được thư mục")
                    }) { Text("Tạo", color = Mc.red, fontWeight = FontWeight.Bold) }
                }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(subdirs, key = { it.path }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { dir = d.file }.padding(vertical = 9.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = Mc.star, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(d.name, color = Mc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF5D626C))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ fm.dialog = null; fm.copyMove(e, dir, move) }) { Text("$verb vào đây", color = Mc.red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ fm.dialog = null }) { Text("Huỷ", color = Mc.muted) } },
    )
}

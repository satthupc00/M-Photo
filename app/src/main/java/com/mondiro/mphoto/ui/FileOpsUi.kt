package com.mondiro.mphoto.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mondiro.mphoto.data.FileOps
import com.mondiro.mphoto.data.MediaItem

/** Ô ảnh trong lưới, hỗ trợ chọn nhiều. */
@Composable
fun GridThumb(vm: AppViewModel, folder: String, item: MediaItem, index: Int) {
    val selecting = vm.selection.isNotEmpty() && vm.selectionFolder == folder
    val sel = selecting && item.id in vm.selection
    val sc by animateFloatAsState(if (sel) 0.86f else 1f, spring(dampingRatio = 0.6f), label = "sel")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pressable(onLongClick = { vm.selectRangeTo(folder, item.id) }) {
                if (selecting) vm.toggleSelect(folder, item.id) else vm.viewer = ViewerState(folder, index)
            },
    ) {
        Thumb(
            item,
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = sc; scaleY = sc }
                .clip(RoundedCornerShape(if (sel) 10.dp else 0.dp)),
        )
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (sel) Mc.red else Color(0x55000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (sel) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
        }
    }
}

/** Thanh trên khi đang chọn nhiều. */
@Composable
fun SelectionTopBar(vm: AppViewModel, total: Int, onSelectAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.Close, "Bỏ chọn") { vm.clearSelection() }
        Text("${vm.selection.size} đã chọn", color = Mc.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconBtn(Icons.Outlined.SelectAll, if (vm.selection.size == total) "Bỏ chọn tất cả" else "Chọn tất cả", tint = if (vm.selection.size == total) Mc.red else Color(0xFFCFD2D8), onClick = onSelectAll)
    }
}

/** Thanh thao tác dưới khi đang chọn nhiều: Chia sẻ, Copy, Di chuyển, Xoá. */
@Composable
fun SelectionBar(vm: AppViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Mc.chrome.copy(alpha = 0.97f))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Mc.line))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BarAction(Icons.Outlined.Share, "Chia sẻ") {
                val items = vm.selectedItems()
                if (items.isEmpty()) return@BarAction
                val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType(if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "Chia sẻ"))
            }
            BarAction(Icons.Outlined.ContentCopy, "Copy") { vm.startCopyMove(vm.selectedItems(), move = false) }
            BarAction(Icons.AutoMirrored.Outlined.DriveFileMove, "Di chuyển") { vm.startCopyMove(vm.selectedItems(), move = true) }
            BarAction(Icons.Outlined.DeleteOutline, "Xoá", tint = Mc.redSoft) { vm.requestDelete(vm.selectedItems()) }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, tint: Color = Mc.text, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = tint, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Xin quyền của Android (xoá / sửa file), hộp chọn thư mục đích, thanh tiến trình. */
@Composable
fun FileOpsHost(vm: AppViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val req = vm.permissionRequest
        vm.permissionRequest = null
        if (res.resultCode == Activity.RESULT_OK) req?.onGranted?.invoke()
    }
    val req = vm.permissionRequest
    LaunchedEffect(req) {
        if (req != null) try { launcher.launch(IntentSenderRequest.Builder(req.sender).build()) } catch (_: Exception) { vm.permissionRequest = null; vm.toast("Không mở được hộp thoại cấp quyền") }
    }

    vm.picker?.let { DestinationPicker(vm, it) }

    vm.busy?.let { msg ->
        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(260.dp).clip(RoundedCornerShape(16.dp)).background(Mc.panel).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Mc.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(color = Mc.red, trackColor = Mc.line, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DestinationPicker(vm: AppViewModel, req: PickerReq) {
    if (com.mondiro.mphoto.data.Files.hasAllFilesAccess()) { BrowseDestPicker(vm, req); return }
    BackHandler { vm.picker = null }
    val anyVideo = req.items.any { it.isVideo }
    val from = req.items.map { it.folder }.toSet()
    // Tạo thư mục mới: chọn nơi tạo (parent) rồi chỉ gõ tên
    var creating by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf(req.defaultNew?.substringBeforeLast('/', "Pictures") ?: "Pictures") }
    var name by remember { mutableStateOf(req.defaultNew?.substringAfterLast('/') ?: "") }
    var choosingParent by remember { mutableStateOf(false) }
    val targets = remember(vm.folders) {
        vm.visible.map { it.path }.filter { p -> p !in from && req.items.all { FileOps.canPlace(p, it.isVideo) } }.sortedBy { it.lowercase() }
    }
    val parents = remember(vm.folders) {
        (FileOps.allowedTops(anyVideo) + vm.visible.map { it.path })
            .filter { p -> req.items.all { FileOps.canPlace(p, it.isVideo) } }
            .distinct().sortedBy { it.lowercase() }
    }
    val cleanName = name.trim()
    val nameOk = cleanName.isNotEmpty() && !cleanName.contains('/')
    val verb = if (req.move) "Di chuyển" else "Copy"
    AlertDialog(
        onDismissRequest = { vm.picker = null },
        containerColor = Mc.panel,
        title = { Text(if (creating) "Thư mục mới" else "$verb ${req.items.size} mục tới…", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (!creating) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { creating = true }.padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, null, tint = Mc.red)
                        Spacer(Modifier.width(12.dp))
                        Text("Thư mục mới…", color = Mc.red, fontWeight = FontWeight.Bold)
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(targets, key = { it }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { vm.performCopyMove(req, p) }.padding(vertical = 9.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Thumb(vm.folders[p]?.cover, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), showVideo = false)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.substringAfterLast('/'), color = Mc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("/$p", color = Mc.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
                    Text("Tạo trong", color = Mc.muted, fontSize = 12.sp)
                    // Nơi tạo: bấm để chọn thư mục khác
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(Mc.chrome)
                            .pressable { choosingParent = !choosingParent }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = Mc.star, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("/$parent", color = Mc.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (choosingParent) "Xong" else "Đổi", color = Mc.red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (choosingParent) LazyColumn(Modifier.heightIn(max = 260.dp).padding(top = 4.dp)) {
                        items(parents, key = { it }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (p == parent) Mc.selected else Color.Transparent)
                                    .pressable { parent = p; choosingParent = false }.padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Folder, null, tint = Mc.star, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("/$p", color = Mc.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.replace("/", "") }, singleLine = true,
                        label = { Text("Tên thư mục mới") },
                        textStyle = TextStyle(fontSize = 15.sp, color = Mc.text),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mc.red, unfocusedBorderColor = Mc.line, cursorColor = Mc.red, focusedLabelColor = Mc.red),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (nameOk) Text("Sẽ tạo: /$parent/$cleanName", color = Mc.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            if (creating) TextButton({ vm.performCopyMove(req, "$parent/$cleanName") }, enabled = nameOk) {
                Text("Tạo & $verb", color = Mc.red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton({ if (creating) creating = false else vm.picker = null }) { Text(if (creating) "Quay lại" else "Huỷ", color = Mc.muted) }
        },
    )
}

/**
 * Chọn nơi đến khi đã có quyền truy cập tất cả file: duyệt từng thư mục trong Bộ nhớ trong,
 * tạo thư mục mới (chỉ gõ tên) ở bất kỳ đâu, rồi bấm "… vào đây".
 */
@Composable
private fun BrowseDestPicker(vm: AppViewModel, req: PickerReq) {
    BackHandler { vm.picker = null }
    val root = com.mondiro.mphoto.data.Files.root
    val startRel = req.defaultNew?.substringBeforeLast('/', "")
        ?: req.items.firstOrNull()?.folder?.takeIf { it != "Bộ nhớ trong" && !it.startsWith("Thẻ nhớ") }?.substringBeforeLast('/', "")
        ?: ""
    var dir by remember { mutableStateOf(java.io.File(root, startRel).takeIf { it.isDirectory } ?: root) }
    var newName by remember { mutableStateOf<String?>(req.defaultNew?.substringAfterLast('/')) }
    var reload by remember { mutableStateOf(0) }
    val subdirs by androidx.compose.runtime.produceState(emptyList<com.mondiro.mphoto.data.FileEntry>(), dir, reload) {
        value = try {
            com.mondiro.mphoto.data.Files.list(dir, false).filter { it.isDir && !(it.path.contains("/Android/data") || it.path.contains("/Android/obb")) }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) { emptyList() }
    }
    val rel = com.mondiro.mphoto.data.Files.rel(dir.absolutePath)
    val atRoot = dir.absolutePath == root.absolutePath
    val verb = if (req.move) "Di chuyển" else "Copy"
    AlertDialog(
        onDismissRequest = { vm.picker = null },
        containerColor = Mc.panel,
        title = { Text("$verb ${req.items.size} mục tới…", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!atRoot) IconBtn(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Lên 1 cấp", modifier = Modifier.size(38.dp)) { dir = dir.parentFile ?: root }
                    Text("/" + rel.ifEmpty { "Bộ nhớ trong" }, color = Mc.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (newName == null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { newName = "" }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, tint = Mc.red)
                    Spacer(Modifier.width(12.dp))
                    Text("Thư mục mới ở đây…", color = Mc.red, fontWeight = FontWeight.Bold)
                } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    OutlinedTextField(
                        value = newName!!, onValueChange = { newName = it.replace("/", "") }, singleLine = true,
                        label = { Text("Tên thư mục mới") },
                        textStyle = TextStyle(fontSize = 14.sp, color = Mc.text),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mc.red, unfocusedBorderColor = Mc.line, cursorColor = Mc.red, focusedLabelColor = Mc.red),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton({
                        val n = newName!!.trim()
                        val f = java.io.File(dir, n)
                        if (n.isNotEmpty() && (f.isDirectory || f.mkdirs())) { dir = f; newName = null; reload++ } else vm.toast("Không tạo được thư mục")
                    }, enabled = newName!!.isNotBlank()) { Text("Tạo", color = Mc.red, fontWeight = FontWeight.Bold) }
                }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(subdirs, key = { it.path }) { d ->
                        val relD = com.mondiro.mphoto.data.Files.rel(d.path)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { dir = d.file }.padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val cover = vm.folders[relD]?.cover
                            if (cover != null) Thumb(cover, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)), showVideo = false)
                            else Icon(Icons.Outlined.Folder, null, tint = Mc.star, modifier = Modifier.size(34.dp).padding(5.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(d.name, color = Mc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${d.children}", color = Mc.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.performCopyMove(req, rel) }) { Text("$verb vào đây", color = Mc.red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ vm.picker = null }) { Text("Huỷ", color = Mc.muted) } },
    )
}

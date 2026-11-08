package com.mondiro.mphoto.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mondiro.mphoto.data.Folder
import com.mondiro.mphoto.data.MediaItem

@Composable
fun StarIcon(size: Dp = 16.dp) = Icon(Icons.Filled.Star, null, tint = Mc.star, modifier = Modifier.size(size))

// ------------------------------------------------------------------ Album

@Composable
fun AlbumGrid(vm: AppViewModel, folders: List<Folder>, fixedCols: Int?, bottomPad: Dp, searching: Boolean = false) {
    if (folders.isEmpty()) {
        EmptyState(
            if (searching) "Không tìm thấy" else if (vm.loading) "Đang quét ảnh…" else "Chưa có ảnh",
            if (searching) "Thử gõ tên thư mục khác" else "Ảnh và video trong máy sẽ hiện ở đây",
        )
        return
    }
    val pins = if (searching) emptyList() else folders.filter { it.path in vm.pinned }
    val rest = if (searching) folders else folders.filter { it.path !in vm.pinned }
    LazyVerticalGrid(
        columns = if (fixedCols != null) GridCells.Fixed(fixedCols) else GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomPad),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (pins.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-pin") { SectionTitle("Đã ghim", icon = { StarIcon(14.dp) }) }
            items(pins, key = { "p:" + it.path }) { f -> AlbumCard(vm, f, Modifier.animateItem()) }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "h-all") {
            SectionTitle(if (searching) "Kết quả · ${rest.size}" else "Tất cả thư mục có ảnh · ${rest.size}")
        }
        items(rest, key = { "a:" + it.path }) { f -> AlbumCard(vm, f, Modifier.animateItem()) }
    }
}

@Composable
fun AlbumCard(vm: AppViewModel, f: Folder, modifier: Modifier = Modifier) {
    val sel = vm.openFolder == f.path
    val border by animateColorAsState(if (sel) Mc.red else Color.Transparent, label = "b")
    val hidden = vm.isHidden(f.path)
    Column(modifier.pressable(onLongClick = { vm.sheetFolder = f.path }) { vm.openFolder = f.path; vm.search = null }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, border, RoundedCornerShape(16.dp)),
        ) {
            Thumb(f.cover, Modifier.fillMaxSize(), showVideo = false)
            if (f.path in vm.pinned) Badge(Modifier.align(Alignment.TopStart)) { StarIcon(15.dp) }
            if (hidden) Badge(Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            if (f.path in vm.gsync) Badge(Modifier.align(Alignment.BottomEnd)) { Icon(Icons.Outlined.CloudUpload, null, tint = Mc.green, modifier = Modifier.size(15.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp)) {
                Text(f.name, color = Mc.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(countLabel(f.count, f.videoCount), color = Mc.muted, fontSize = 12.sp, maxLines = 1)
            }
            IconBtn(Icons.Outlined.MoreVert, "Tuỳ chọn", tint = Mc.muted, modifier = Modifier.size(36.dp)) { vm.sheetFolder = f.path }
        }
    }
}

@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .padding(7.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0x73000000)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ------------------------------------------------------------------ Thư mục

@Composable
fun Crumbs(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("Bộ nhớ trong", vm.path.isEmpty()) { vm.path = emptyList(); vm.openFolder = null }
        vm.path.forEachIndexed { i, seg ->
            Text("›", color = Color(0xFF555A64), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 2.dp))
            Crumb(seg, i == vm.path.lastIndex) { vm.path = vm.path.take(i + 1); vm.openFolder = null }
        }
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (current) Color.White else Mc.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) Mc.panel else Color.Transparent)
            .pressable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun FolderList(vm: AppViewModel, bottomPad: Dp) {
    val nodes = vm.nodes
    val own = vm.ownHere
    if (nodes.isEmpty() && own == null) {
        EmptyState(if (vm.loading) "Đang quét ảnh…" else "Trống", "Không có ảnh trong thư mục này")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomPad),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (own != null) {
            item(key = "own") { SectionTitle("Ảnh trong thư mục này") }
            item(key = "own-row") {
                NodeRow(
                    vm, path = own.path, title = own.name, sub = countLabel(own.count, own.videoCount) + " mục",
                    cover = own.cover, isDir = false, modifier = Modifier.animateItem(),
                ) { vm.openFolder = own.path }
            }
        }
        if (nodes.isNotEmpty()) item(key = "h") { SectionTitle("Thư mục · ${nodes.size}") }
        items(nodes, key = { it.path }) { n ->
            val sub = buildString {
                if (n.subCount > 0) append("${n.subCount} thư mục con · ")
                append(fmtCount(n.count)); append(" mục")
                if (n.videoCount > 0) append(" · ${n.videoCount} video")
            }
            NodeRow(vm, n.path, n.name, sub, n.cover, isDir = n.subCount > 0, modifier = Modifier.animateItem()) {
                if (n.subCount > 0) { vm.path = n.path.split('/'); vm.openFolder = null } else vm.openFolder = n.path
            }
        }
    }
}

@Composable
private fun NodeRow(
    vm: AppViewModel, path: String, title: String, sub: String, cover: MediaItem?, isDir: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val sel = vm.openFolder == path
    val bg by animateColorAsState(if (sel) Mc.selected else Color.Transparent, label = "bg")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .pressable(onLongClick = { vm.sheetFolder = path }, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))) {
            Thumb(cover, Modifier.fillMaxSize(), showVideo = false)
            if (isDir) Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x8C000000)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Folder, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (path in vm.pinned) { StarIcon(15.dp); Spacer(Modifier.width(4.dp)) }
                Text(title, color = Mc.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (vm.isHidden(path)) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.VisibilityOff, null, tint = Mc.muted, modifier = Modifier.size(14.dp)) }
                if (path in vm.gsync) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.CloudUpload, null, tint = Mc.green, modifier = Modifier.size(14.dp)) }
            }
            Text(sub, color = Mc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isDir) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF5D626C))
        else IconBtn(Icons.Outlined.MoreVert, "Tuỳ chọn", tint = Mc.muted, modifier = Modifier.size(38.dp)) { vm.sheetFolder = path }
    }
}

// ------------------------------------------------------------------ Lưới ảnh 1 thư mục

@Composable
fun FolderDetail(vm: AppViewModel, f: Folder, compact: Boolean, bottomPad: Dp, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        TopBar(
            f.name,
            onBack = if (compact) ({ vm.openFolder = null }) else null,
            star = f.path in vm.pinned,
            actions = {
                IconBtn(Icons.Outlined.MoreVert, "Tuỳ chọn") { vm.sheetFolder = f.path }
                actions()
            },
        )
        Text(
            buildString {
                append("/"); append(f.path); append(" · "); append(countLabel(f.count, f.videoCount)); append(" mục")
                if (f.path in vm.gsync) append(" · Sync Google Photos")
            },
            color = Mc.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 88.dp else 112.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = bottomPad),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(f.items.size, key = { f.items[it].id }) { i ->
                Thumb(
                    f.items[i],
                    Modifier
                        .aspectRatio(1f)
                        .pressable { vm.viewer = ViewerState(f.path, i) },
                )
            }
        }
    }
}

@Composable
fun EmptyState(title: String, sub: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PhotoLibrary, null, tint = Mc.muted, modifier = Modifier.size(40.dp))
            Text(title, color = Mc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Text(sub, color = Mc.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun EmptyDetail() = EmptyState("Chọn 1 thư mục", "Ảnh và video sẽ hiện ở đây")

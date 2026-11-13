package com.mondiro.mphoto.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private val PERMS = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private val NAV_H = 64.dp

@Composable
fun MPhotoRoot(vm: AppViewModel) {
    val ctx = LocalContext.current
    fun granted() = PERMS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermission(granted()) }

    LaunchedEffect(Unit) {
        if (granted()) vm.onPermission(true) else launcher.launch(PERMS)
        vm.checkUpdate(manual = false)
    }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        val g = granted()
        if (g != vm.hasPermission) vm.onPermission(g) else if (g && vm.loadedOnce) vm.reload(debounce = true)
        onPauseOrDispose { }
    }

    // Hộp thoại cài bản cập nhật: mở từ màn hình app (Activity) để Android không chặn
    LaunchedEffect(Unit) {
        com.mondiro.mphoto.update.InstallEvents.confirm.collect { i ->
            if (i != null) {
                com.mondiro.mphoto.update.InstallEvents.confirm.value = null
                try { (ctx.findActivity() ?: ctx).startActivity(i) } catch (e: Exception) {
                    vm.toast("Không mở được hộp thoại cài: ${e.message}")
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Mc.chrome)) {
        val expanded = maxWidth >= 600.dp
        if (!vm.hasPermission) PermissionScreen { if (!granted()) launcher.launch(PERMS) }
        else if (expanded) Expanded(vm) else Compact(vm)

        BackHandler(enabled = vm.viewer == null && vm.sheetFolder == null) {
            when {
                vm.selection.isNotEmpty() -> vm.clearSelection()
                vm.search != null -> vm.search = null
                vm.openFolder != null -> vm.openFolder = null
                vm.tab == Tab.Folder && vm.path.isNotEmpty() -> { vm.path = vm.path.dropLast(1); vm.openFolder = null }
                vm.tab == Tab.Files && vm.files.segment == "browse" && !vm.files.atRoot -> vm.files.up()
                else -> (ctx.findActivity())?.moveTaskToBack(true)
            }
        }

        vm.sheetFolder?.let { FolderSheet(vm, it, wide = expanded) }
        vm.viewer?.let { Viewer(vm, it) }
        Dialogs(vm)
        FileOpsHost(vm)
        FilesOverlays(vm, wide = expanded)
        ToastHost(vm, bottom = if (!expanded && vm.viewer == null) NAV_H + 16.dp else 24.dp)
    }
}

// ------------------------------------------------------------------ Màn ngoài (1 cột)

@Composable
private fun Compact(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val open = vm.openFolder?.let { vm.folders[it] }
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = if (open != null && vm.tab != Tab.Settings && vm.search == null) "d:" + open.path else "m:" + vm.tab,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "screen",
                ) { key ->
                    val f = if (key.startsWith("d:")) vm.folders[key.removePrefix("d:")] else null
                    if (f != null) FolderDetail(vm, f, compact = true, bottomPad = NAV_H + 64.dp) { TopActions(vm) }
                    else MainPane(vm, fixedCols = null, bottomPad = NAV_H + 64.dp, showActions = true)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (vm.selection.isNotEmpty()) SelectionBar(vm)
            else {
                Credit(Modifier.align(Alignment.End).padding(end = 10.dp, bottom = 6.dp))
                BottomNav(vm)
            }
        }
    }
}

@Composable
private fun BottomNav(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().background(Mc.chrome.copy(alpha = 0.96f))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Mc.line))
        BoxWithConstraints(Modifier.fillMaxWidth().height(NAV_H)) {
            val w = maxWidth / 4
            val idx = vm.tab.ordinal
            val x by animateDpAsState(w * idx, tween(220), label = "ind")
            Box(Modifier.offset(x = x + w * 0.3f).width(w * 0.4f).height(2.dp).clip(RoundedCornerShape(2.dp)).background(Mc.red))
            Row(Modifier.fillMaxSize()) {
                NavItem(Icons.Outlined.GridView, "Album", vm.tab == Tab.Album, Modifier.weight(1f)) { vm.selectTab(Tab.Album) }
                NavItem(Icons.Outlined.Folder, "Thư mục", vm.tab == Tab.Folder, Modifier.weight(1f)) { vm.selectTab(Tab.Folder) }
                NavItem(Icons.Outlined.Description, "File", vm.tab == Tab.Files, Modifier.weight(1f)) { vm.selectTab(Tab.Files) }
                NavItem(Icons.Outlined.Settings, "Cài đặt", vm.tab == Tab.Settings, Modifier.weight(1f)) { vm.selectTab(Tab.Settings) }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = if (on) Mc.red else Mc.muted, modifier = Modifier.size(24.dp))
        Text(label, color = if (on) Color.White else Mc.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Danh sách chính (Album / Thư mục / Cài đặt / Tìm kiếm). */
@Composable
private fun MainPane(vm: AppViewModel, fixedCols: Int?, bottomPad: androidx.compose.ui.unit.Dp, showActions: Boolean) {
    Column(Modifier.fillMaxSize()) {
        when {
            vm.search != null -> SearchBar(vm)
            vm.tab == Tab.Folder && vm.path.isNotEmpty() -> TopBar(vm.path.last(), onBack = { vm.path = vm.path.dropLast(1); vm.openFolder = null }) { if (showActions) TopActions(vm) }
            else -> TopBar(
                when (vm.tab) { Tab.Album -> "Album"; Tab.Folder -> "Thư mục"; Tab.Files -> "File"; Tab.Settings -> "Cài đặt" },
            ) {
                if (vm.tab == Tab.Album) AlbumSizeButton(vm)
                if (showActions) TopActions(vm)
            }
        }
        if (vm.tab == Tab.Folder && vm.search == null) Crumbs(vm)
        Box(Modifier.weight(1f)) {
            when {
                vm.tab == Tab.Files -> FilesScreen(vm, bottomPad)
                vm.search != null -> AlbumGrid(vm, vm.searchResults, fixedCols, bottomPad, searching = vm.search!!.isNotBlank())
                vm.tab == Tab.Album -> AlbumGrid(vm, vm.visible, fixedCols, bottomPad)
                vm.tab == Tab.Folder -> FolderList(vm, bottomPad)
                else -> SettingsScreen(vm, bottomPad)
            }
        }
    }
}

@Composable
private fun SearchBar(vm: AppViewModel) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(vm.search) { vm.searchGridState.scrollToItem(0) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Đóng tìm") { vm.search = null }
        TextField(
            value = vm.search ?: "", onValueChange = { vm.search = it }, singleLine = true,
            placeholder = { Text(if (vm.tab == Tab.Files) "Tìm file…" else "Tìm thư mục…", color = Mc.muted) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Mc.muted) },
            trailingIcon = { if (!vm.search.isNullOrEmpty()) IconBtn(Icons.Outlined.Close, "Xoá") { vm.search = "" } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Mc.panel, unfocusedContainerColor = Mc.panel,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Mc.red,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f).focusRequester(fr),
        )
    }
}

// ------------------------------------------------------------------ Màn trong (list + detail)

@Composable
private fun Expanded(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Rail(vm)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Mc.line))
            if (vm.tab == Tab.Settings) {
                Column(Modifier.weight(1f).statusBarsPadding()) {
                    TopBar("Cài đặt") { TopActions(vm) }
                    Box(Modifier.weight(1f)) { SettingsScreen(vm, bottomPad = 40.dp) }
                }
            } else {
                // Màn trong: mở thư mục là xem toàn khung, không chia đôi danh sách / ảnh
                Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                    val open = vm.openFolder?.let { vm.folders[it] }
                    AnimatedContent(
                        targetState = if (open != null && vm.search == null) "d:" + open.path else "m:" + vm.tab,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "screen",
                    ) { key ->
                        val f = if (key.startsWith("d:")) vm.folders[key.removePrefix("d:")] else null
                        if (f != null) FolderDetail(vm, f, compact = false, showBack = true, bottomPad = 96.dp) { TopActions(vm) }
                        else MainPane(vm, fixedCols = null, bottomPad = 48.dp, showActions = true)
                    }
                    if (vm.selection.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter)) { SelectionBar(vm) }
                }
            }
        }
        if (vm.selection.isEmpty()) Credit(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 10.dp, bottom = 8.dp))
    }
}

@Composable
private fun Rail(vm: AppViewModel) {
    Column(
        Modifier.width(80.dp).fillMaxHeight().statusBarsPadding().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RailItem(Icons.Outlined.GridView, "Album", vm.tab == Tab.Album) { vm.selectTab(Tab.Album) }
        RailItem(Icons.Outlined.Folder, "Thư mục", vm.tab == Tab.Folder) { vm.selectTab(Tab.Folder) }
        RailItem(Icons.Outlined.Description, "File", vm.tab == Tab.Files) { vm.selectTab(Tab.Files) }
        RailItem(Icons.Outlined.Settings, "Cài đặt", vm.tab == Tab.Settings) { vm.selectTab(Tab.Settings) }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Mc.selected else Color.Transparent)
            .pressable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (on) Mc.red else Mc.muted, modifier = Modifier.size(24.dp))
        Text(label, color = if (on) Color.White else Mc.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ Xin quyền

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PhotoLibrary, null, tint = Mc.red, modifier = Modifier.size(56.dp))
            Text("Cho phép xem ảnh và video", color = Mc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Text("M Photo cần quyền đọc ảnh, video trong máy để hiện thư viện. Ảnh không được gửi đi đâu cả.", color = Mc.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Text(
                "Cho phép", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Mc.red).pressable(onClick = onRequest).padding(horizontal = 28.dp, vertical = 12.dp),
            )
            Text(
                "Mở cài đặt quyền", color = Mc.muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).pressable {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }.padding(10.dp),
            )
        }
        Credit(Modifier.align(Alignment.BottomEnd))
    }
}

/** Nút đổi cỡ ô Album: To -> Vừa -> Nhỏ -> To. */
@Composable
private fun AlbumSizeButton(vm: AppViewModel) {
    val icon = when (vm.albumSize) { 0 -> Icons.Outlined.Apps; 1 -> Icons.Outlined.ViewModule; else -> Icons.Outlined.GridView }
    IconBtn(icon, "Cỡ ô Album") {
        val next = if (vm.albumSize == 0) 2 else vm.albumSize - 1
        vm.changeAlbumSize(next)
        vm.toast("Cỡ ô: " + when (next) { 0 -> "Nhỏ"; 1 -> "Vừa"; else -> "To" })
    }
}

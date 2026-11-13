package com.mondiro.mphoto.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mondiro.mphoto.data.Rules
import com.mondiro.mphoto.update.UpdState
import com.mondiro.mphoto.update.Updater

@Composable
fun SettingsScreen(vm: AppViewModel, bottomPad: Dp) {
    val ctx = LocalContext.current
    LazyColumn(
        flingBehavior = rememberFastFling(vm.scrollSpeed),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottomPad),
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                GroupTitle("Hiển thị")
                Group {
                    Item(
                        if (vm.showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        "Hiện thư mục ẩn", "${vm.hiddenCount} thư mục · cần vân tay để mở",
                        trailing = { Switch(vm.showHidden) },
                    ) {
                        if (vm.showHidden) vm.setShowHiddenAuthed(false)
                        else authenticate(ctx, onOk = { vm.setShowHiddenAuthed(true) }, onFail = { vm.toast(it) })
                    }
                    Item(Icons.Outlined.GridView, "Mở app ở chế độ", null, trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Album", vm.defaultTab == "album", Mc.red) { vm.setDefault("album") }
                            Chip("Thư mục", vm.defaultTab == "folder", Mc.red) { vm.setDefault("folder") }
                        }
                    })
                    Item(Icons.Outlined.GridView, "Cỡ ô ảnh", "Chụm 2 ngón trên lưới để đổi nhanh", trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Nhỏ", vm.albumSize == 0, Mc.red) { vm.changeAlbumSize(0) }
                            Chip("Vừa", vm.albumSize == 1, Mc.red) { vm.changeAlbumSize(1) }
                            Chip("To", vm.albumSize == 2, Mc.red) { vm.changeAlbumSize(2) }
                        }
                    })
                }

                GroupTitle("Tốc độ")
                Group {
                    SpeedRow(Icons.Outlined.PanTool, "Tốc độ kéo ảnh (pan)", "Khi đang zoom, kéo ảnh nhanh hơn", vm.panSpeed, 1f, 4f) { vm.setPan(it) }
                    SpeedRow(Icons.Outlined.SwapVert, "Tốc độ cuộn danh sách", "Vuốt nhanh thì danh sách trôi xa hơn", vm.scrollSpeed, 1f, 4f) { vm.setScroll(it) }
                }

                GroupTitle("Loại trừ thư mục")
                Group {
                    Item(Icons.Outlined.Shield, "Tự loại trừ hệ thống, icon, sticker", "Ẩn ảnh rác của app khỏi thư viện", trailing = { Switch(vm.autoExclude) }) { vm.toggleAuto() }
                    Rules.SYSTEM.forEach { s ->
                        val on = s.id !in vm.sysOff
                        RuleRow(s.title, "Mặc định · " + s.patterns.joinToString(", "), dim = !vm.autoExclude) {
                            Chip(if (on) "Loại trừ" else "Tắt", on, Mc.redSoft) { vm.toggleSys(s.id) }
                        }
                    }
                    vm.userRules.forEachIndexed { i, r ->
                        RuleRow(r.pattern, "Bạn thêm", mono = true) {
                            Chip(if (r.include) "Không loại trừ" else "Loại trừ", true, if (r.include) Mc.green else Mc.redSoft) { vm.toggleRule(i) }
                            IconBtn(Icons.Outlined.DeleteOutline, "Xoá", tint = Mc.muted, modifier = Modifier.size(38.dp)) { vm.deleteRule(i) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().pressable { vm.dialog = "addRule" }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Add, null, tint = Mc.red)
                        Spacer(Modifier.width(10.dp))
                        Text("Thêm đường dẫn", color = Mc.red, fontWeight = FontWeight.Bold)
                    }
                    Item(Icons.Outlined.Block, "Thư mục đang bị loại trừ", "${vm.excluded.size} thư mục · chạm để xem, đưa trở lại", chevron = true) { vm.dialog = "excluded" }
                }

                GroupTitle("Google Photos")
                Group {
                    vm.gsync.sorted().forEach { p ->
                        Item(Icons.Outlined.CloudUpload, p.substringAfterLast('/'), "/$p", trailing = {
                            Chip("Đang sync", true, Mc.green) { vm.sheetFolder = p }
                        }) { vm.sheetFolder = p }
                    }
                    Item(Icons.Outlined.CloudUpload, "Mở Sao lưu của Google Photos", "Bật/tắt thư mục cần sync trong app Google Photos", chevron = true) {
                        openGooglePhotos(ctx, vm)
                    }
                }

                GroupTitle("Phiên bản")
                Group {
                    Item(
                        Icons.Outlined.Refresh, "Kiểm tra cập nhật",
                        when (val s = vm.upd) {
                            UpdState.Checking -> "Đang kiểm tra…"
                            UpdState.Latest -> "Đã là bản mới nhất"
                            is UpdState.Available -> "Có bản mới v${s.release.version}"
                            is UpdState.Downloading -> "Đang tải… " + downloadLabel(s)
                            UpdState.Error -> "Không kiểm tra được, thử lại sau"
                            UpdState.Idle -> "Tự kiểm tra khi mở app"
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("v" + Updater.currentVersion, color = Mc.muted, fontSize = 13.sp)
                                if (vm.upd is UpdState.Latest) LatestBadge()
                            }
                        },
                    ) {
                        val s = vm.upd
                        if (s is UpdState.Available) vm.updateDialog = s.release else vm.checkUpdate(manual = true)
                    }
                    Item(Icons.Outlined.AutoAwesome, "What's new", "Có gì mới ở bản này", chevron = true) { vm.dialog = "whatsnew" }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun GroupTitle(t: String) =
    Text(t.uppercase(), color = Mc.red, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp))

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mc.panel)) { content() }
}

@Composable
private fun Item(
    icon: ImageVector, title: String, sub: String?, chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Mc.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Mc.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Mc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mc.muted)
    }
}

@Composable
private fun RuleRow(title: String, sub: String, dim: Boolean = false, mono: Boolean = false, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dim) 0.45f else 1f).padding(start = 48.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Mc.text, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Mc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun Chip(text: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) color.copy(alpha = 0.13f) else Color.Transparent, label = "c")
    val fg by animateColorAsState(if (on) color else Mc.muted, label = "f")
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, if (on) color.copy(alpha = 0.4f) else Mc.line, RoundedCornerShape(14.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun Switch(on: Boolean) {
    val bg by animateColorAsState(if (on) Mc.red else Color(0xFF474C55), label = "sw")
    val x by animateDpAsState(if (on) 18.dp else 0.dp, spring(dampingRatio = 0.6f), label = "x")
    Box(Modifier.size(width = 42.dp, height = 24.dp).clip(RoundedCornerShape(12.dp)).background(bg).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(18.dp).clip(CircleShape).background(Color.White))
    }
}

/** Thanh trượt chỉnh tốc độ (bước 0.25x), hiện số "x1.50" bên phải. */
@Composable
private fun SpeedRow(icon: ImageVector, title: String, sub: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Mc.muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Mc.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(sub, color = Mc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("x" + "%.2f".format(value), color = Mc.red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = { onChange((Math.round(it * 4f) / 4f).coerceIn(min, max)) },
            valueRange = min..max,
            steps = ((max - min) * 4).toInt() - 1,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Mc.red, inactiveTrackColor = Color(0xFF474C55),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.padding(start = 34.dp),
        )
    }
}

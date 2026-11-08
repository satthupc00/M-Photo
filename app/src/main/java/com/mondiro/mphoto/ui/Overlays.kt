package com.mondiro.mphoto.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mondiro.mphoto.update.UpdState
import kotlinx.coroutines.delay
import org.json.JSONObject

// ------------------------------------------------------------------ Vân tay

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

fun authenticate(ctx: Context, onOk: () -> Unit, onFail: (String) -> Unit) {
    val act = ctx.findActivity() ?: return onFail("Không mở được xác thực")
    val bm = BiometricManager.from(act)
    val auth = when {
        bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        bm.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        else -> return onFail("Máy chưa cài vân tay hoặc khoá màn hình")
    }
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                errorCode != BiometricPrompt.ERROR_CANCELED
            ) onFail(errString.toString())
        }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Hiện thư mục ẩn")
            .setSubtitle("Chạm cảm biến vân tay để tiếp tục")
            .setAllowedAuthenticators(auth)
            .build()
    )
}

// ------------------------------------------------------------------ Google Photos

private const val GPHOTOS = "com.google.android.apps.photos"

fun openGooglePhotos(ctx: Context, vm: AppViewModel) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(GPHOTOS)
    try {
        if (launch != null) ctx.startActivity(launch)
        else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GPHOTOS")))
    } catch (_: Exception) {
        vm.toast("Không mở được Google Photos")
    }
}

// ------------------------------------------------------------------ Bảng thao tác thư mục

@Composable
fun FolderSheet(vm: AppViewModel, path: String, wide: Boolean) {
    BackHandler { vm.sheetFolder = null }
    val f = vm.folders[path]
    val cover = f?.cover ?: vm.folders.values.filter { it.path.startsWith("$path/") }.mapNotNull { it.cover }.maxByOrNull { it.date }
    val pinned = path in vm.pinned
    val hidden = path in vm.hidden
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .clickable(remember { MutableInteractionSource() }, null) { vm.sheetFolder = null },
        contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn()) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(if (wide) RoundedCornerShape(22.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Mc.panel)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4E57)))
                Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(cover, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), showVideo = false)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(path.substringAfterLast('/'), color = Mc.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("/$path", color = Mc.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp).size(1.dp).background(Mc.line))
                Action(if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder, if (pinned) "Bỏ ghim" else "Ghim lên đầu", "Đánh dấu sao, luôn nằm trên cùng", tint = if (pinned) Mc.star else Mc.text) {
                    vm.sheetFolder = null; vm.togglePin(path)
                }
                Action(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (hidden) "Bỏ ẩn" else "Ẩn thư mục", "Chỉ hiện khi mở khoá bằng vân tay") {
                    vm.sheetFolder = null; vm.toggleHide(path)
                }
                Action(Icons.Outlined.CloudUpload, "Sync lên Google Photos", if (path in vm.gsync) "Đang đánh dấu sync · chạm để xem" else "Mở cài đặt sao lưu thư mục", tint = if (path in vm.gsync) Mc.green else Mc.text) {
                    vm.sheetFolder = null; vm.dialog = "gp:$path"
                }
                Action(Icons.Outlined.Block, "Loại trừ khỏi thư viện", "Không hiện thư mục này nữa", tint = Mc.redSoft) {
                    vm.sheetFolder = null; vm.excludeFolder(path)
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, sub: String, tint: Color = Mc.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = Mc.muted, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ Hộp thoại

@Composable
fun Dialogs(vm: AppViewModel) {
    val ctx = LocalContext.current
    val d = vm.dialog
    val close = { vm.dialog = null }
    when {
        d == "addRule" -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                containerColor = Mc.panel,
                title = { Text("Thêm đường dẫn", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Nhập đường dẫn thư mục (vd: Android/media/com.zing.zalo/stickers), hoặc chỉ tên thư mục để áp cho mọi nơi (vd: Stickers). Dùng * thay cho chữ bất kỳ.", color = Mc.muted, fontSize = 13.sp)
                        Spacer(Modifier.size(12.dp))
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            placeholder = { Text("Pictures/Instagram", color = Mc.muted) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Mc.text),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mc.red, unfocusedBorderColor = Mc.line, cursorColor = Mc.red),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Row {
                        TextButton({ vm.addRule(text, include = true); close() }, enabled = text.isNotBlank()) { Text("Không loại trừ", color = Mc.green) }
                        TextButton({ vm.addRule(text, include = false); close() }, enabled = text.isNotBlank()) { Text("Loại trừ", color = Mc.red, fontWeight = FontWeight.Bold) }
                    }
                },
                dismissButton = { TextButton(close) { Text("Huỷ", color = Mc.muted) } },
            )
        }

        d == "excluded" -> AlertDialog(
            onDismissRequest = close,
            containerColor = Mc.panel,
            title = { Text("Thư mục đang bị loại trừ", fontWeight = FontWeight.Bold) },
            text = {
                if (vm.excluded.isEmpty()) Text("Không có thư mục nào", color = Mc.muted)
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(vm.excluded, key = { it.path }) { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = Mc.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("/${f.path} · ${fmtCount(f.count)}", color = Mc.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Chip("Không loại trừ", true, Mc.green) { vm.includeFolder(f.path) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(close) { Text("Xong", color = Mc.red) } },
        )

        d == "whatsnew" -> {
            val wn = remember {
                try { JSONObject(ctx.assets.open("whatsnew.json").bufferedReader().readText()) } catch (_: Exception) { null }
            }
            AlertDialog(
                onDismissRequest = close,
                containerColor = Mc.panel,
                title = { Text("What's new", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.heightIn(max = 460.dp)) {
                        if (wn != null) {
                            val date = wn.optString("date").split("-").reversed().joinToString("/")
                            Text("v${wn.optString("version")} · $date", color = Mc.muted, fontSize = 13.sp)
                            for ((key, label) in listOf("new" to "Mới", "improved" to "Cải thiện", "fixed" to "Sửa lỗi")) {
                                val arr = wn.optJSONArray(key) ?: continue
                                if (arr.length() == 0) continue
                                Text(label.uppercase(), color = Mc.red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                for (i in 0 until arr.length()) Text("•  " + arr.getString(i), color = Mc.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                        val s = vm.upd
                        if (s is UpdState.Available) {
                            Text("Có bản mới v${s.release.version}", color = Mc.green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                },
                confirmButton = {
                    val s = vm.upd
                    if (s is UpdState.Available) TextButton({ close(); vm.downloadAndInstall(s.release) }) { Text("Cập nhật", color = Mc.green, fontWeight = FontWeight.Bold) }
                    else TextButton(close) { Text("Đóng", color = Mc.red) }
                },
            )
        }

        d != null && d.startsWith("gp:") -> {
            val p = d.removePrefix("gp:")
            val on = p in vm.gsync
            AlertDialog(
                onDismissRequest = close,
                containerColor = Mc.panel,
                title = { Text("Sync \"${p.substringAfterLast('/')}\"", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Google Photos tự sao lưu thư mục này khi bạn bật nó trong app Google Photos:", color = Mc.text, fontSize = 14.sp)
                        Step("1", "Mở Google Photos › chạm ảnh đại diện")
                        Step("2", "Cài đặt Photos › Sao lưu")
                        Step("3", "Sao lưu thư mục trên thiết bị › bật \"${p.substringAfterLast('/')}\"")
                        Text("M Photo sẽ gắn dấu đám mây xanh cho thư mục này để bạn dễ nhận biết.", color = Mc.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = {
                    TextButton({ vm.setSync(p, true); close(); openGooglePhotos(ctx, vm) }) { Text("Mở Google Photos", color = Mc.red, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    if (on) TextButton({ vm.setSync(p, false); close(); vm.toast("Đã bỏ dấu sync") }) { Text("Bỏ dấu sync", color = Mc.muted) }
                    else TextButton(close) { Text("Huỷ", color = Mc.muted) }
                },
            )
        }
    }

    vm.updateDialog?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.updateDialog = null },
            containerColor = Mc.panel,
            title = { Text("Có bản mới v${r.version}", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { Text(r.notes.ifBlank { "Bản cập nhật mới của M Photo" }.replace("**", "").replace("### ", "").replace("## ", ""), color = Mc.text, fontSize = 14.sp) }
                }
            },
            confirmButton = { TextButton({ vm.downloadAndInstall(r) }) { Text("Cập nhật ngay", color = Mc.green, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ vm.updateDialog = null }) { Text("Để sau", color = Mc.muted) } },
        )
    }

    val s = vm.upd
    if (s is UpdState.Downloading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LinearProgressIndicator(progress = { s.progress }, color = Mc.green, trackColor = Color.Transparent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Step(n: String, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(Mc.selected), contentAlignment = Alignment.Center) {
            Text(n, color = Mc.red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(t, color = Mc.text, fontSize = 13.sp)
    }
}

// ------------------------------------------------------------------ Toast

@Composable
fun ToastHost(vm: AppViewModel, bottom: androidx.compose.ui.unit.Dp) {
    val t = vm.toast ?: return
    LaunchedEffect(t.id) { delay(2400); if (vm.toast?.id == t.id) vm.toast = null }
    val shown = remember(t.id) { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize().padding(bottom = bottom).navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(shown, enter = scaleIn(initialScale = 0.9f) + fadeIn(), exit = fadeOut()) {
            Text(
                t.text, color = Mc.text, fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF33363D))
                    .drawBehind { drawRect(Mc.red, size = size.copy(width = 3.dp.toPx()), topLeft = Offset.Zero) }
                    .padding(start = 15.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

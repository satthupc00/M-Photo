package com.mondiro.mphoto.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.mondiro.mphoto.data.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Viewer(vm: AppViewModel, state: ViewerState) {
    val folder = vm.folders[state.folder] ?: return
    val items = folder.items
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = state.index.coerceIn(0, items.lastIndex)) { items.size }
    var bars by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<MediaItem?>(null) }
    val ctx = LocalContext.current
    val view = LocalView.current

    // Ẩn thanh trạng thái khi xem ảnh toàn màn hình
    DisposableEffect(bars) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (bars) ctl?.show(WindowInsetsCompat.Type.systemBars()) else ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(pager.currentPage) { vm.viewer = state.copy(index = pager.currentPage) }
    BackHandler { vm.viewer = null }

    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { vm.toast("Đã chuyển vào thùng rác"); vm.reload() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { items[it].id }, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item, isCurrent = page == pager.currentPage) { bars = !bars }
            else ZoomImage(item) { bars = !bars }
        }
        val cur = items.getOrNull(pager.currentPage)
        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) { vm.viewer = null }
                Column(Modifier.weight(1f)) {
                    Text(cur?.name ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1}/${fmtCount(items.size)} · ${cur?.let { fmtDate(it.date) } ?: ""}", color = Color(0xFFB8BCC4), fontSize = 12.sp)
                }
                IconBtn(Icons.Outlined.Info, "Thông tin", tint = Color.White) { info = cur }
            }
        }
        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .navigationBarsPadding()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconBtn(Icons.Outlined.Share, "Chia sẻ", tint = Color.White) {
                    cur ?: return@IconBtn
                    val send = Intent(Intent.ACTION_SEND).setType(cur.mime).putExtra(Intent.EXTRA_STREAM, cur.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Chia sẻ"))
                }
                IconBtn(Icons.Outlined.OpenInNew, "Mở bằng app khác", tint = Color.White) {
                    cur ?: return@IconBtn
                    val open = Intent(Intent.ACTION_VIEW).setDataAndType(cur.uri, cur.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { ctx.startActivity(Intent.createChooser(open, "Mở bằng")) } catch (_: Exception) { vm.toast("Không có app nào mở được") }
                }
                IconBtn(Icons.Outlined.DeleteOutline, "Xoá", tint = Color.White) {
                    cur ?: return@IconBtn
                    try {
                        val pi = MediaStore.createTrashRequest(ctx.contentResolver, listOf(cur.uri), true)
                        trash.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } catch (_: Exception) { vm.toast("Không xoá được ảnh này") }
                }
            }
        }
    }

    info?.let { m ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton({ info = null }) { Text("Đóng", color = Mc.red) } },
            title = { Text("Thông tin", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoLine("Tên", m.name)
                    InfoLine("Thư mục", "/" + m.folder)
                    InfoLine("Ngày", fmtDate(m.date))
                    if (m.width > 0) InfoLine("Kích thước", "${m.width} × ${m.height}")
                    InfoLine("Dung lượng", fmtSize(m.size))
                    if (m.isVideo) InfoLine("Thời lượng", fmtDur(m.duration))
                }
            },
            containerColor = Mc.panel,
        )
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Column {
        Text(k, color = Mc.muted, fontSize = 12.sp)
        Text(v, color = Mc.text, fontSize = 14.sp)
    }
}

private fun fmtDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun ZoomImage(item: MediaItem, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - p) * 1.5f, 2.5f)
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            scale = (scale * z).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else clamp(offset + p, scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = item.uri, contentDescription = item.name, contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}

@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    var playing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        val mc = MediaController(c)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoURI(item.uri)
                        setOnPreparedListener { start(); mc.show(2500) }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = item.uri, contentDescription = item.name, contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
            )
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
                    .pressable { playing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Phát", tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
    }
}

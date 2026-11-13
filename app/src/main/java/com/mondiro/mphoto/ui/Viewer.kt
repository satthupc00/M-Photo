package com.mondiro.mphoto.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RotateRight
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.mondiro.mphoto.data.ImageEdit
import com.mondiro.mphoto.data.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Phép xoay/lật đang xem thử của 1 ảnh (chưa lưu). rotation tính bằng độ, cộng dồn để animation xoay đúng chiều. */
private data class Edit(val rotation: Float = 0f, val flip: Boolean = false, val base: Long = 0L) {
    val isIdentity: Boolean get() = ((rotation.roundToInt() % 360) + 360) % 360 == 0 && !flip
}

@Composable
fun Viewer(vm: AppViewModel, state: ViewerState) {
    val folder = vm.folders[state.folder] ?: return
    val items = folder.items
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = state.index.coerceIn(0, items.lastIndex)) { items.size }
    var bars by remember { mutableStateOf(true) }
    var videoFull by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<MediaItem?>(null) }
    val edits = remember { mutableStateMapOf<Long, Edit>() }
    var saving by remember { mutableStateOf<MediaItem?>(null) }
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Phép xoay/lật chỉ áp cho đúng phiên bản file (sau khi lưu, file đổi ngày sửa -> bỏ phép cũ)
    fun editOf(m: MediaItem): Edit = edits[m.id]?.takeIf { it.base == m.modified } ?: Edit(base = m.modified)

    DisposableEffect(bars, videoFull) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (bars && !videoFull) ctl?.show(WindowInsetsCompat.Type.systemBars()) else ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(pager.currentPage) {
        val s = state.copy(index = pager.currentPage)
        vm.viewer = s; vm.lastViewed = s; videoFull = false
    }
    BackHandler { vm.viewer = null }
    BackHandler(enabled = videoFull) { videoFull = false }

    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { vm.toast("Đã chuyển vào thùng rác"); vm.reload() }
    }

    fun doSave(item: MediaItem) {
        val e = edits[item.id]?.takeIf { it.base == item.modified } ?: return
        scope.launch {
            try {
                ImageEdit.save(ctx, item, e.rotation.roundToInt(), e.flip)
                vm.toast("Đã lưu ảnh")
                vm.reload()
            } catch (ex: Exception) {
                vm.toast("Không lưu được ảnh này")
            }
        }
    }
    val writeAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val it2 = saving; saving = null
        if (it.resultCode == Activity.RESULT_OK && it2 != null) doSave(it2)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { items[it].id }, beyondViewportPageCount = 1, userScrollEnabled = !videoFull, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            val current = page == pager.currentPage
            if (item.isVideo) VideoPage(item, current, bars, videoFull && current, setFull = { videoFull = it }, onTap = { bars = !bars }, setBars = { bars = it })
            else ImagePage(
                item, current, editOf(item),
                onRotate = { d -> val e = editOf(item); edits[item.id] = e.copy(rotation = e.rotation + d) },
            ) { bars = !bars }
        }

        val cur = items.getOrNull(pager.currentPage)
        val edit = cur?.let { c -> edits[c.id]?.takeIf { it.base == c.modified } }
        AnimatedVisibility(bars && !videoFull, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) { vm.viewer = null }
                Column(Modifier.weight(1f)) {
                    Text(cur?.name ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1}/${fmtCount(items.size)} · ${cur?.let { fmtDate(it.date) } ?: ""}", color = Color(0xFFB8BCC4), fontSize = 12.sp, maxLines = 1)
                }
                if (cur != null && edit != null && !edit.isIdentity) {
                    Text(
                        "Đặt lại", color = Color(0xFFCFD2D8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { edits.remove(cur.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Text(
                        "Lưu", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Mc.red).pressable {
                            if (!ImageEdit.canSave(cur.mime)) {
                                vm.toast("Ảnh dạng ${cur.mime.substringAfter('/').uppercase()} chỉ xoay để xem, chưa lưu được")
                            } else try {
                                saving = cur
                                val pi = MediaStore.createWriteRequest(ctx.contentResolver, listOf(cur.uri))
                                writeAccess.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                            } catch (_: Exception) { saving = null; vm.toast("Không lưu được ảnh này") }
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconBtn(Icons.Outlined.Info, "Thông tin", tint = Color.White) { info = cur }
            }
        }
        AnimatedVisibility(bars && !videoFull, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (cur != null && !cur.isVideo) {
                    IconBtn(Icons.Outlined.RotateRight, "Xoay", tint = Color.White) {
                        val e = editOf(cur)
                        // Khi đang lật, xoay theo chiều ngược lại để trên màn hình vẫn là xoay phải
                        edits[cur.id] = e.copy(rotation = e.rotation + if (e.flip) -90f else 90f)
                    }
                    IconBtn(Icons.Outlined.Flip, "Lật ngang", tint = Color.White) {
                        val e = editOf(cur)
                        edits[cur.id] = e.copy(flip = !e.flip)
                    }
                }
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
                IconBtn(Icons.Outlined.ContentCopy, "Copy tới…", tint = Color.White) { cur?.let { vm.startCopyMove(listOf(it), move = false) } }
                IconBtn(Icons.AutoMirrored.Outlined.DriveFileMove, "Di chuyển tới…", tint = Color.White) { cur?.let { vm.startCopyMove(listOf(it), move = true) } }
                IconBtn(Icons.Outlined.DeleteOutline, "Xoá", tint = Color.White) { cur?.let { vm.requestDelete(listOf(it)) } }
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

// ------------------------------------------------------------------ Ảnh

@Composable
private fun ImagePage(item: MediaItem, isCurrent: Boolean, edit: Edit, onRotate: (Float) -> Unit, onTap: () -> Unit) {
    val z = rememberZoomState(item.id)
    val scope = rememberCoroutineScope()
    LaunchedEffect(isCurrent) { if (!isCurrent) z.reset() }
    // Góc xoay đã chốt (animation mượt khi bấm nút Xoay hoặc khi thả tay xoay 2 ngón)
    val rotA = remember(item.id) { Animatable(edit.rotation) }
    var lastMod by remember(item.id) { mutableLongStateOf(item.modified) }
    LaunchedEffect(edit.rotation, item.modified) {
        if (item.modified != lastMod) { lastMod = item.modified; rotA.snapTo(edit.rotation) }
        else rotA.animateTo(edit.rotation, tween(320, easing = FastOutSlowInEasing))
    }
    val flipA = remember(item.id) { Animatable(if (edit.flip) -1f else 1f) }
    var lastModFlip by remember(item.id) { mutableLongStateOf(item.modified) }
    LaunchedEffect(edit.flip, item.modified) {
        val t = if (edit.flip) -1f else 1f
        if (item.modified != lastModFlip) { lastModFlip = item.modified; flipA.snapTo(t) } else flipA.animateTo(t, tween(320, easing = FastOutSlowInEasing))
    }
    val flipX = flipA.value
    val sign = if (edit.flip) -1f else 1f
    val rot = rotA.value + sign * z.freeRotation
    var intrinsic by remember(item.id, item.modified) { mutableStateOf(Size.Unspecified) }

    BoxWithConstraints(
        Modifier.fillMaxSize().zoomGestures(
            z, scope,
            onRotateEnd = { free ->
                // Thả tay: tự bắt về góc 90° gần nhất
                val snap = (free / 90f).roundToInt() * 90f
                scope.launch {
                    rotA.snapTo(rotA.value + sign * free)
                    z.freeRotation = 0f
                    if (snap != 0f) onRotate(sign * snap)
                    else rotA.animateTo(edit.rotation, tween(260, easing = FastOutSlowInEasing))
                }
            },
            onTap = onTap,
        ),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Hệ số thu nhỏ để ảnh xoay vẫn nằm gọn trong khung
        val k = if (intrinsic.isSpecified && intrinsic.width > 0 && intrinsic.height > 0 && w > 0 && h > 0) {
            val fs = min(w / intrinsic.width, h / intrinsic.height)
            val fw = intrinsic.width * fs; val fh = intrinsic.height * fs
            // Tính khung vừa theo góc đã chốt (không theo góc đang xoay tay) để tâm xoay giữa 2 ngón không bị trôi
            val rad = Math.toRadians(rotA.value.toDouble())
            val c = abs(cos(rad)).toFloat(); val s = abs(sin(rad)).toFloat()
            val bw = fw * c + fh * s; val bh = fw * s + fh * c
            min(w / bw, h / bh) / min(w / fw, h / fh)
        } else 1f
        AsyncImage(
            model = imageRequest(item, 4096),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            onSuccess = { intrinsic = it.painter.intrinsicSize },
            modifier = Modifier
                .fillMaxSize()
                .zoomLayer(z)
                .graphicsLayer { scaleX = flipX }
                .graphicsLayer { rotationZ = rot; scaleX = k; scaleY = k },
        )
        ZoomBadge(z, rot - edit.rotation, Modifier.align(Alignment.Center))
    }
}

/** Số % zoom (và góc xoay khi đang xoay 2 ngón) hiện giữa màn hình rồi tự ẩn. */
@Composable
fun ZoomBadge(z: ZoomState, rotating: Float = 0f, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(z.zoomEvent) { if (z.zoomEvent > 0) { visible = true; delay(900); visible = false } }
    val showRot = abs(z.freeRotation) > 0.5f
    AnimatedVisibility(visible || showRot, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            if (showRot) "${((z.freeRotation % 360)).roundToInt()}°" else "${(z.scale * 100).roundToInt()}%",
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ------------------------------------------------------------------ Video

@Composable
private fun VideoPage(
    item: MediaItem, isCurrent: Boolean, bars: Boolean, full: Boolean, setFull: (Boolean) -> Unit,
    onTap: () -> Unit, setBars: (Boolean) -> Unit,
) {
    // Mở video (hoặc vuốt tới video) là tự phát
    var playing by remember(item.id) { mutableStateOf(isCurrent) }
    LaunchedEffect(isCurrent) { playing = isCurrent }
    val z = rememberZoomState(item.id)
    val scope = rememberCoroutineScope()
    LaunchedEffect(isCurrent) { if (!isCurrent) z.reset() }

    Box(Modifier.fillMaxSize().zoomGestures(z, scope, onTap = onTap), contentAlignment = Alignment.Center) {
        ZoomBadge(z, modifier = Modifier.align(Alignment.Center))
        if (playing) {
            VideoPlayer(item, z, bars, setBars, full, setFull)
        } else {
            AsyncImage(
                model = imageRequest(item, 2048), contentDescription = item.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomLayer(z),
            )
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(0x88000000)).pressable { playing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Phát", tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
    }
}

@Composable
private fun VideoPlayer(item: MediaItem, z: ZoomState, bars: Boolean, setBars: (Boolean) -> Unit, full: Boolean, setFull: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    var aspect by remember { mutableFloatStateOf(if (item.width > 0 && item.height > 0) item.width / item.height.toFloat() else 16f / 9f) }
    var rotation by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(item.duration) }
    var scrubbing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    @Suppress("DEPRECATION")
                    rotation = videoSize.unappliedRotationDegrees
                }
            }
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(s: Int) { ended = s == Player.STATE_ENDED; if (ended) setBars(true) }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) pos = player.currentPosition
            if (player.duration > 0) dur = player.duration
            delay(150)
        }
    }
    // Đang phát thì tự ẩn thanh công cụ sau 3 giây
    LaunchedEffect(isPlaying, bars) { if (isPlaying && bars) { delay(3000); setBars(false) } }
    // Toàn màn hình: video ngang thì xoay ngang máy, video dọc giữ dọc
    val activity = ctx.findActivity()
    val wide = (if (rotation % 180 != 0) 1f / aspect else aspect) >= 1f
    DisposableEffect(full, wide) {
        activity?.requestedOrientation = when {
            !full -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            wide -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(full) { if (full) z.reset() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val disp = if (rotation % 180 != 0) 1f / aspect else aspect
        Box(Modifier.zoomLayer(z).aspectRatio(disp)) {
            AndroidView(
                factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
                modifier = Modifier.fillMaxSize().rotated(rotation),
            )
        }

        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = if (full) 16.dp else 70.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xB3000000))
                    .padding(start = 2.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Tạm dừng" else "Phát", tint = Color.White) {
                    if (isPlaying) player.pause() else { if (ended) player.seekTo(0); player.play() }
                }
                Text(fmtDur(pos), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                SeekBar(
                    fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    onScrub = { f -> scrubbing = true; pos = (f * dur).toLong() },
                    onSeek = { f -> player.seekTo((f * dur).toLong()); pos = (f * dur).toLong(); scrubbing = false },
                    modifier = Modifier.weight(1f),
                )
                Text(fmtDur(dur), color = Color(0xFFB8BCC4), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                IconBtn(if (full) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (full) "Thoát toàn màn hình" else "Toàn màn hình", tint = Color.White) {
                    setFull(!full); setBars(true)
                }
            }
        }
    }
}

/** Đặt view theo góc xoay của video (khi máy chưa tự xoay hình). */
private fun Modifier.rotated(deg: Int): Modifier =
    if (deg % 180 == 0) graphicsLayer { rotationZ = deg.toFloat() }
    else layout { m, c ->
        val p = m.measure(Constraints.fixed(c.maxHeight, c.maxWidth))
        layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) }
    }.graphicsLayer { rotationZ = deg.toFloat() }

/** Thanh tua mảnh: vạch 3dp, nút tròn 12dp, vùng chạm cao 32dp. */
@Composable
private fun SeekBar(fraction: Float, onScrub: (Float) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragFrac else fraction
    val thumb by animateFloatAsState(if (dragging) 16f else 12f, label = "thumb")
    BoxWithConstraints(
        modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; dragFrac = (o.x / size.width).coerceIn(0f, 1f); onScrub(dragFrac) },
                    onDragEnd = { dragging = false; onSeek(dragFrac) },
                    onDragCancel = { dragging = false },
                ) { ch, dx -> ch.consume(); dragFrac = (dragFrac + dx / size.width).coerceIn(0f, 1f); onScrub(dragFrac) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF)))
        Box(Modifier.fillMaxWidth(shown).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Mc.red))
        Box(
            Modifier
                .offset(x = maxWidth * shown - (thumb / 2).dp)
                .size(thumb.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

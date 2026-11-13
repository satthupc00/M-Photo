package com.mondiro.mphoto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mondiro.mphoto.data.MediaItem
import com.mondiro.mphoto.update.UpdState

/** Bấm thì lún nhẹ (scale .96, spring), giữ lâu thì rung + gọi onLongClick. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    val haptic = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .combinedClickable(
            interactionSource = src,
            indication = LocalIndication.current,
            onLongClick = onLongClick?.let { lc -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            onClick = onClick,
        )
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, tint: Color = Color(0xFFCFD2D8), modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(23.dp)) }
}

/** Nút kiểm tra cập nhật: xoay khi đang kiểm tra, xanh + chữ "latest" khi là bản mới nhất, nhấp nháy khi có bản mới. */
@Composable
fun UpdateButton(vm: AppViewModel) {
    val st = vm.upd
    val inf = rememberInfiniteTransition(label = "upd")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rot")
    val pulse by inf.animateFloat(1f, 0.4f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    val green = st is UpdState.Latest || st is UpdState.Available || st is UpdState.Downloading
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .pressable {
                    when (st) {
                        is UpdState.Available -> vm.updateDialog = st.release
                        is UpdState.Downloading -> vm.toast("Đang tải… " + downloadLabel(st))
                        else -> vm.checkUpdate(manual = true)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Refresh, "Kiểm tra cập nhật",
                tint = if (green) Mc.green else Color(0xFFCFD2D8),
                modifier = Modifier
                    .size(23.dp)
                    .rotate(if (st is UpdState.Checking || st is UpdState.Downloading) rot else 0f)
                    .graphicsLayer { alpha = if (st is UpdState.Available) pulse else 1f },
            )
        }
        if (st is UpdState.Latest) LatestBadge()
        if (st is UpdState.Available) Text("Mới", color = Mc.green, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun LatestBadge() {
    val sc = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { sc.animateTo(1f, spring(dampingRatio = 0.5f)) }
    Text(
        "latest", color = Mc.latest, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .graphicsLayer { scaleX = sc.value; scaleY = sc.value }
            .clip(RoundedCornerShape(20.dp))
            .background(Mc.latest.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** Hàng icon góc phải trên: Tìm + Cập nhật. */
@Composable
fun TopActions(vm: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.Search, "Tìm") { vm.search = if (vm.search == null) "" else null }
        UpdateButton(vm)
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    star: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", onClick = onBack)
        if (star) {
            Icon(Icons.Filled.Star, null, tint = Mc.star, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title, color = Mc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun Thumb(item: MediaItem?, modifier: Modifier = Modifier, showVideo: Boolean = true) {
    Box(modifier.background(Mc.panel)) {
        if (item != null) {
            AsyncImage(model = imageRequest(item), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.isVideo && showVideo) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x88000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Text(fmtDur(item.duration), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, icon: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), color = Mc.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    }
}

@Composable
fun Credit(modifier: Modifier = Modifier) {
    Text(
        "Created by Mondiro", color = Mc.credit, fontSize = 10.5.sp,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Mc.chrome.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtCount(n: Int): String = "%,d".format(n).replace(',', '.')

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

fun countLabel(count: Int, videos: Int): String =
    fmtCount(count) + if (videos > 0) " · $videos video" else ""

/** Yêu cầu tải ảnh có gắn ngày sửa, để ảnh vừa xoay/lật xong không bị lấy lại bản cũ trong bộ nhớ đệm. */
@Composable
fun imageRequest(item: MediaItem, maxSize: Int? = null): coil3.request.ImageRequest {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(item.uri, item.modified, maxSize) {
        coil3.request.ImageRequest.Builder(ctx)
            .data(item.uri)
            .memoryCacheKeyExtra("mod", item.modified.toString())
            .apply { if (maxSize != null) size(coil3.size.Size(maxSize, maxSize)) }
            .build()
    }
}

/** Cuộn nhanh hơn: nhân tốc độ vuốt (fling) theo Cài đặt. */
@Composable
fun rememberFastFling(mult: Float): androidx.compose.foundation.gestures.FlingBehavior {
    val base = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    return remember(base, mult) {
        object : androidx.compose.foundation.gestures.FlingBehavior {
            override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float =
                with(base) { performFling(initialVelocity * mult) }
        }
    }
}

/** "45% · 1.2 MB/s · còn 3 giây · từ máy chủ CDN" */
fun downloadLabel(d: com.mondiro.mphoto.update.UpdState.Downloading): String = buildString {
    append("${(d.progress * 100).toInt()}%")
    if (d.speed > 0) append(" · " + if (d.speed >= 1_048_576) "%.1f MB/s".format(d.speed / 1048576.0) else "${d.speed / 1024} KB/s")
    if (d.eta >= 0) append(" · còn " + if (d.eta >= 60) "${d.eta / 60} phút ${d.eta % 60} giây" else "${d.eta} giây")
    if (d.source.isNotEmpty()) append(" · từ ${d.source}")
}

/** Cỡ ô theo bậc (dùng chung cho Album và lưới ảnh): 0 nhỏ, 1 vừa, 2 to. */
fun cellSize(level: Int): androidx.compose.ui.unit.Dp = when (level) { 0 -> 88.dp; 1 -> 112.dp; else -> 150.dp }

/**
 * Chụm / mở 2 ngón trên lưới để đổi cỡ ô nhanh qua 3 bậc (mỗi lần chụm đổi 1 bậc).
 * Bắt sự kiện ở lượt đầu (Initial) để 2 ngón không bị lưới hiểu thành cuộn.
 */
@Composable
fun Modifier.pinchResize(vm: AppViewModel): Modifier {
    val haptic = LocalHapticFeedback.current
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var acc = 1f
            do {
                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (ev.changes.count { it.pressed } >= 2) {
                    acc *= ev.calculateZoom()
                    // Chụm tiếp thì đổi tiếp bậc kế (to -> vừa -> nhỏ và ngược lại)
                    if (acc > 1.2f || acc < 0.83f) {
                        if (vm.stepSize(if (acc > 1f) 1 else -1)) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        acc = 1f
                    }
                    ev.changes.forEach { it.consume() }
                }
            } while (ev.changes.any { it.pressed })
        }
    }
}

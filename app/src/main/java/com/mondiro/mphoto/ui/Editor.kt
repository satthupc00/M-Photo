package com.mondiro.mphoto.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mondiro.mphoto.data.EditOp
import com.mondiro.mphoto.data.EditRender
import com.mondiro.mphoto.data.MediaItem
import com.mondiro.mphoto.data.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val PALETTE = listOf(
    Color(0xFFFFFFFF), Color(0xFF111111), Color(0xFFE5383B), Color(0xFFF5A623), Color(0xFFFFE14D),
    Color(0xFF3DDC8A), Color(0xFF3D9BFF), Color(0xFF9B5CFF), Color(0xFFFF6FB5),
)
private val RATIOS = listOf("Tự do" to 0f, "Gốc" to -1f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

/** Khung cắt chuẩn hoá 0..1. */
private data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val isFull get() = l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f
}

@Composable
fun PhotoEditor(vm: AppViewModel, item: MediaItem) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("draw") }
    val ops = remember { mutableStateListOf<EditOp>() }
    val redo = remember { mutableStateListOf<EditOp>() }
    var straight by remember { mutableFloatStateOf(0f) }
    var crop by remember { mutableStateOf(CropRect()) }
    var ratio by remember { mutableStateOf(0f) }
    var tool by remember { mutableStateOf(Stroke.PEN) }
    var color by remember { mutableStateOf(Color(0xFFE5383B)) }
    var hue by remember { mutableFloatStateOf(-1f) }
    var size by remember { mutableFloatStateOf(12f) }
    var saveDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }

    // Ảnh xem trước (độ phân giải vừa màn hình để thao tác mượt)
    var src by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) {
        src = try { withContext(Dispatchers.IO) { EditRender.decode(ctx, item.uri, 1800) } } catch (e: Exception) { null }
        if (src == null) { vm.toast("Không mở được ảnh để sửa"); vm.editing = null }
    }
    // Nét vẽ cuối cùng (chưa "nướng" vào ảnh) hiện ở lớp riêng để vẽ / tẩy mượt
    val firstTrailingDraw = run { var i = ops.size; while (i > 0 && ops[i - 1] is EditOp.Draw) i--; i }
    val baseOps = ops.subList(0, firstTrailingDraw).toList()
    val liveStrokes = ops.subList(firstTrailingDraw, ops.size).map { (it as EditOp.Draw).stroke }
    var base by remember { mutableStateOf<ImageBitmap?>(null) }
    var baseW by remember { mutableStateOf(1) }
    var baseH by remember { mutableStateOf(1) }
    LaunchedEffect(src, baseOps) {
        val s = src ?: return@LaunchedEffect
        val b = withContext(Dispatchers.Default) { EditRender.applyAll(s, baseOps) }
        baseW = b.width; baseH = b.height
        base = b.asImageBitmap()
    }

    fun push(op: EditOp) { ops.add(op); redo.clear() }
    fun commitPending() {
        if (abs(straight) > 0.05f) { push(EditOp.Straighten(straight)); straight = 0f }
        if (!crop.isFull) { push(EditOp.Crop(crop.l, crop.t, crop.r, crop.b)); crop = CropRect() }
    }
    val dirty = ops.isNotEmpty() || abs(straight) > 0.05f || !crop.isFull
    fun close() { if (dirty) discardDialog = true else vm.editing = null }
    BackHandler { close() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0D0E10))) {
        // ----- thanh trên
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Outlined.Close, "Huỷ", tint = Color.White) { close() }
            Text("Sửa ảnh", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            val canUndo = ops.isNotEmpty() || abs(straight) > 0.05f || !crop.isFull
            IconBtn(Icons.AutoMirrored.Filled.Undo, "Hoàn tác", tint = if (canUndo) Color.White else Color(0x55FFFFFF)) {
                when {
                    !crop.isFull -> crop = CropRect()
                    abs(straight) > 0.05f -> straight = 0f
                    ops.isNotEmpty() -> redo.add(ops.removeAt(ops.lastIndex))
                }
            }
            IconBtn(Icons.AutoMirrored.Filled.Redo, "Làm lại", tint = if (redo.isNotEmpty()) Color.White else Color(0x55FFFFFF)) {
                if (redo.isNotEmpty()) ops.add(redo.removeAt(redo.lastIndex))
            }
            Text(
                "Lưu", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(12.dp)).background(Mc.red)
                    .pressable { commitPending(); if (ops.isEmpty()) vm.toast("Chưa có thay đổi nào") else saveDialog = true }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }

        // ----- vùng ảnh
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val b = base
            if (b != null) {
                val density = LocalDensity.current
                val maxW = with(density) { maxWidth.toPx() }; val maxH = with(density) { maxHeight.toPx() }
                val sc = min(maxW / baseW, maxH / baseH)
                val dw = baseW * sc; val dh = baseH * sc
                val dwDp = with(density) { dw.toDp() }; val dhDp = with(density) { dh.toDp() }
                val cover = EditRender.coverScale(straight, baseW.toFloat(), baseH.toFloat())
                Box(Modifier.size(dwDp, dhDp).clipToBounds()) {
                    Image(
                        b, null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = straight; scaleX = cover; scaleY = cover },
                    )
                    // Lớp nét vẽ (Tẩy chỉ xoá nét, không xoá ảnh)
                    var current by remember { mutableStateOf<Stroke?>(null) }
                    Canvas(
                        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .then(
                                if (tab == "draw") Modifier.pointerInput(tool, color, size) {
                                    awaitEachGesture {
                                        val d = awaitFirstDown()
                                        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
                                        xs += d.position.x / this.size.width; ys += d.position.y / this.size.height
                                        // cỡ nét tính theo px màn hình -> quy về tỉ lệ ảnh
                                        fun mk() = Stroke(xs.toFloatArray(), ys.toFloatArray(), color.toArgb(), size / this.size.width, tool)
                                        current = mk()
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                            if (!ch.pressed) break
                                            xs += (ch.position.x / this.size.width).coerceIn(0f, 1f); ys += (ch.position.y / this.size.height).coerceIn(0f, 1f)
                                            ch.consume()
                                            current = mk()
                                        }
                                        current?.let { push(EditOp.Draw(it)) }
                                        current = null
                                    }
                                } else Modifier,
                            ),
                    ) {
                        (liveStrokes + listOfNotNull(current)).forEach { s ->
                            val path = Path()
                            for (k in s.xs.indices) {
                                val x = s.xs[k] * this.size.width; val y = s.ys[k] * this.size.height
                                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            if (s.xs.size == 1) path.lineTo(s.xs[0] * this.size.width + 0.1f, s.ys[0] * this.size.height)
                            drawPath(
                                path, Color(s.color),
                                alpha = if (s.kind == Stroke.MARKER) 0.4f else 1f,
                                style = DrawStroke(width = s.width * this.size.width * (if (s.kind == Stroke.MARKER) 2.2f else 1f), cap = StrokeCap.Round, join = StrokeJoin.Round),
                                blendMode = if (s.kind == Stroke.ERASER) BlendMode.Clear else BlendMode.SrcOver,
                            )
                        }
                    }
                    if (tab == "crop") CropOverlay(crop, ratioPx(ratio, baseW, baseH), baseW, baseH) { crop = it }
                }
            } else Text("Đang mở ảnh…", color = Mc.muted)
        }

        // ----- bảng công cụ
        Column(Modifier.fillMaxWidth().background(Mc.chrome).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tab) {
                    "crop" -> RATIOS.forEach { (label, r) ->
                        Chip(label, ratio == r, Mc.redSoft) {
                            ratio = r
                            val rp = ratioPx(r, baseW, baseH)
                            if (rp > 0) crop = fitRatio(rp, baseW, baseH)
                        }
                    }
                    "rotate" -> {
                        BigTool(Icons.AutoMirrored.Filled.RotateLeft, "Xoay trái") { commitPending(); push(EditOp.Rotate(-90)) }
                        BigTool(Icons.AutoMirrored.Filled.RotateRight, "Xoay phải") { commitPending(); push(EditOp.Rotate(90)) }
                        BigTool(Icons.Outlined.Flip, "Lật ngang") { commitPending(); push(EditOp.Flip(true)) }
                        BigTool(Icons.Outlined.Flip, "Lật dọc", rotateIcon = 90f) { commitPending(); push(EditOp.Flip(false)) }
                    }
                    else -> {
                        Chip("Bút", tool == Stroke.PEN, Mc.redSoft) { tool = Stroke.PEN }
                        Chip("Bút dạ", tool == Stroke.MARKER, Mc.redSoft) { tool = Stroke.MARKER }
                        Chip("Tẩy", tool == Stroke.ERASER, Mc.redSoft) { tool = Stroke.ERASER }
                        Spacer(Modifier.width(4.dp))
                        PALETTE.forEach { c -> Swatch(Brush.linearGradient(listOf(c, c)), color == c && hue < 0) { color = c; hue = -1f; if (tool == Stroke.ERASER) tool = Stroke.PEN } }
                        Swatch(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), hue >= 0) {
                            if (hue < 0) hue = 0f
                            color = Color.hsv(hue, 0.85f, 1f); if (tool == Stroke.ERASER) tool = Stroke.PEN
                        }
                    }
                }
            }
            // thanh trượt
            when (tab) {
                "rotate" -> SliderRow("Nghiêng", "${straight.toInt()}°", straight, -45f..45f) { straight = it }
                "draw" -> {
                    if (hue >= 0) SliderRow("Màu", "", hue, 0f..359f, hueTrack = true) { hue = it; color = Color.hsv(it, 0.85f, 1f) }
                    SliderRow("Cỡ nét", "${size.toInt()}px", size, 2f..60f) { size = it }
                }
                else -> Text("Kéo 4 góc hoặc giữa khung để cắt", color = Mc.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            // tab
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                EdTab(Icons.Outlined.Crop, "Cắt", tab == "crop") { if (tab != "crop") { commitPending(); tab = "crop" } }
                EdTab(Icons.Outlined.Rotate90DegreesCw, "Xoay & lật", tab == "rotate") { if (tab != "rotate") { commitPending(); tab = "rotate" } }
                EdTab(Icons.Outlined.Draw, "Vẽ", tab == "draw") { if (tab != "draw") { commitPending(); tab = "draw" } }
            }
        }
    }

    if (saveDialog) {
        val ext = item.name.substringAfterLast('.', "")
        val keepFormat = item.mime.lowercase() in setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
        val newName = item.name.substringBeforeLast('.') + " (1)." + (if (keepFormat) ext else "jpg")
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            containerColor = Mc.panel,
            title = { Text("Lưu ảnh", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(item.name, color = Mc.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    if (keepFormat) SaveOption(Icons.Outlined.Save, "Lưu đè ảnh gốc", "Thay ảnh cũ bằng ảnh đã sửa") {
                        saveDialog = false; vm.saveEdit(item, ops.toList(), overwrite = true)
                    }
                    SaveOption(Icons.Outlined.ContentCopy, "Lưu thành ảnh mới", newName) {
                        saveDialog = false; vm.saveEdit(item, ops.toList(), overwrite = false)
                    }
                }
            },
            confirmButton = { TextButton({ saveDialog = false }) { Text("Huỷ", color = Mc.muted) } },
        )
    }
    if (discardDialog) AlertDialog(
        onDismissRequest = { discardDialog = false },
        containerColor = Mc.panel,
        title = { Text("Bỏ các thay đổi?", fontWeight = FontWeight.Bold) },
        text = { Text("Ảnh chưa được lưu. Thoát bây giờ sẽ mất những gì vừa sửa.", color = Mc.text) },
        confirmButton = { TextButton({ discardDialog = false; vm.editing = null }) { Text("Bỏ thay đổi", color = Mc.red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ discardDialog = false }) { Text("Ở lại", color = Mc.muted) } },
    )
}

/** Tỉ lệ khung theo pixel: 0 = tự do, -1 = tỉ lệ gốc của ảnh. */
private fun ratioPx(r: Float, w: Int, h: Int): Float = if (r == -1f) w.toFloat() / h else r

private fun fitRatio(rp: Float, w: Int, h: Int): CropRect {
    // khung lớn nhất có tỉ lệ rp, nằm giữa ảnh
    var cw = 1f
    var ch = cw * w / (h * rp)
    if (ch > 1f) { ch = 1f; cw = ch * h * rp / w }
    return CropRect((1 - cw) / 2, (1 - ch) / 2, (1 + cw) / 2, (1 + ch) / 2)
}

/** Khung cắt: kéo 4 góc để đổi cỡ (giữ tỉ lệ nếu có), kéo giữa để di chuyển. */
@Composable
private fun CropOverlay(rect: CropRect, rp: Float, imgW: Int, imgH: Int, onChange: (CropRect) -> Unit) {
    var r by remember { mutableStateOf(rect) }
    LaunchedEffect(rect) { r = rect }
    Box(
        Modifier.fillMaxSize().pointerInput(rp) {
            var mode = -1 // 0 TL,1 TR,2 BL,3 BR,4 move
            detectDragGestures(
                onDragStart = { p ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val corners = listOf(Offset(r.l * w, r.t * h), Offset(r.r * w, r.t * h), Offset(r.l * w, r.b * h), Offset(r.r * w, r.b * h))
                    val near = corners.indices.minByOrNull { (corners[it] - p).getDistance() }!!
                    mode = if ((corners[near] - p).getDistance() < 48.dp.toPx()) near else 4
                },
                onDrag = { ch, d ->
                    ch.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val dx = d.x / w; val dy = d.y / h
                    r = if (mode == 4) {
                        val mx = dx.coerceIn(-r.l, 1 - r.r); val my = dy.coerceIn(-r.t, 1 - r.b)
                        CropRect(r.l + mx, r.t + my, r.r + mx, r.b + my)
                    } else {
                        // góc đang kéo và góc đối diện (đứng yên)
                        val ax = if (mode == 0 || mode == 2) r.r else r.l
                        val ay = if (mode == 0 || mode == 1) r.b else r.t
                        var px = ((if (mode == 0 || mode == 2) r.l else r.r) + dx).coerceIn(0f, 1f)
                        var py = ((if (mode == 0 || mode == 1) r.t else r.b) + dy).coerceIn(0f, 1f)
                        val minS = 0.08f
                        var cw = max(abs(px - ax), minS); var chh = max(abs(py - ay), minS)
                        if (rp > 0) {
                            chh = cw * imgW / (imgH * rp)
                            val roomY = if (py < ay) ay else 1 - ay
                            if (chh > roomY) { chh = roomY; cw = chh * imgH * rp / imgW }
                        }
                        val roomX = if (px < ax) ax else 1 - ax
                        if (cw > roomX) { cw = roomX; if (rp > 0) chh = cw * imgW / (imgH * rp) }
                        px = if ((if (mode == 0 || mode == 2) -1 else 1) < 0) ax - cw else ax + cw
                        py = if ((if (mode == 0 || mode == 1) -1 else 1) < 0) ay - chh else ay + chh
                        CropRect(min(px, ax), min(py, ay), max(px, ax), max(py, ay))
                    }
                },
                onDragEnd = { onChange(r) },
            )
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val l = r.l * w; val t = r.t * h; val rr = r.r * w; val bb = r.b * h
            val dim = Color(0x8C000000)
            drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(w, t))
            drawRect(dim, Offset(0f, bb), androidx.compose.ui.geometry.Size(w, h - bb))
            drawRect(dim, Offset(0f, t), androidx.compose.ui.geometry.Size(l, bb - t))
            drawRect(dim, Offset(rr, t), androidx.compose.ui.geometry.Size(w - rr, bb - t))
            val grid = Color(0x66FFFFFF)
            for (k in 1..2) {
                drawLine(grid, Offset(l + (rr - l) * k / 3, t), Offset(l + (rr - l) * k / 3, bb), 1.dp.toPx())
                drawLine(grid, Offset(l, t + (bb - t) * k / 3), Offset(rr, t + (bb - t) * k / 3), 1.dp.toPx())
            }
            drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(rr - l, bb - t), style = DrawStroke(2.dp.toPx()))
            val c = 22.dp.toPx(); val sw = 4.dp.toPx()
            for ((x, y, sx, sy) in listOf(listOf(l, t, 1f, 1f), listOf(rr, t, -1f, 1f), listOf(l, bb, 1f, -1f), listOf(rr, bb, -1f, -1f))) {
                drawLine(Color.White, Offset(x, y), Offset(x + c * sx, y), sw)
                drawLine(Color.White, Offset(x, y), Offset(x, y + c * sy), sw)
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, hueTrack: Boolean = false, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Mc.muted, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (hueTrack) Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 59.8f, 0.85f, 1f) })),
            )
            Slider(
                value = v, onValueChange = onChange, valueRange = range,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = if (hueTrack) Color.Transparent else Mc.red,
                    inactiveTrackColor = if (hueTrack) Color.Transparent else Color(0xFF474C55),
                ),
            )
        }
        Text(value, color = Mc.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}

@Composable
private fun Swatch(brush: Brush, on: Boolean, onClick: () -> Unit) {
    val s by animateFloatAsState(if (on) 1.18f else 1f, spring(dampingRatio = 0.5f), label = "sw")
    Box(
        Modifier.size(30.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(brush)
            .border(if (on) 2.dp else 1.dp, if (on) Color.White else Color(0x55000000), CircleShape)
            .pressable(onClick = onClick),
    )
}

@Composable
private fun BigTool(icon: ImageVector, label: String, rotateIcon: Float = 0f, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Mc.text, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotateIcon })
        Text(label, color = Mc.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EdTab(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(24.dp).height(2.dp).offset(y = (-6).dp).background(if (on) Mc.red else Color.Transparent))
        Icon(icon, label, tint = if (on) Mc.red else Mc.muted, modifier = Modifier.size(22.dp))
        Text(label, color = if (on) Color.White else Mc.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SaveOption(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF))
            .pressable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Mc.text)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Mc.text, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Mc.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

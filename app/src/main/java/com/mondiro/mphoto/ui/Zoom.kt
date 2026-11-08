package com.mondiro.mphoto.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

const val MAX_ZOOM = 10f
const val DOUBLE_TAP_ZOOM = 3f

/** Trạng thái phóng to / kéo của 1 ảnh hoặc video trong trình xem. */
@Stable
class ZoomState(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var size by mutableStateOf(IntSize.Zero)
    /** Góc xoay tự do đang xoay bằng 2 ngón (chưa thả tay). */
    var freeRotation by mutableFloatStateOf(0f)
    /** Đang có thao tác zoom (để hiện số %). */
    var touching by mutableStateOf(false)
    /** Tăng mỗi lần mức zoom đổi, để hiện số % rồi tự ẩn. */
    var zoomEvent by mutableStateOf(0)
    private var anim: Job? = null

    val zoomed: Boolean get() = scale > 1.02f

    private fun clamped(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx.coerceAtLeast(0f), mx.coerceAtLeast(0f)), o.y.coerceIn(-my.coerceAtLeast(0f), my.coerceAtLeast(0f)))
    }

    /** Phóng theo hệ số z quanh điểm f (toạ độ trong khung), giữ điểm f đứng yên dưới ngón tay. */
    fun zoomBy(z: Float, f: Offset, pan: Offset = Offset.Zero, min: Float = 0.6f, max: Float = MAX_ZOOM) {
        anim?.cancel()
        val ns = (scale * z).coerceIn(min, max)
        if (ns != scale) zoomEvent++
        val rz = ns / scale
        val c = Offset(size.width / 2f, size.height / 2f)
        val fc = f - c
        val t = Offset(offsetX, offsetY)
        val nt = t * rz + fc * (1f - rz) + pan
        scale = ns
        val cl = if (ns >= 1f) clamped(nt, ns) else nt
        offsetX = cl.x; offsetY = cl.y
    }

    fun panBy(p: Offset) {
        anim?.cancel()
        val c = clamped(Offset(offsetX, offsetY) + p, scale)
        offsetX = c.x; offsetY = c.y
    }

    /** Chuyển mượt tới mức zoom s, lấy điểm f làm tâm. */
    fun animateTo(s: Float, f: Offset? = null) {
        anim?.cancel()
        val s0 = scale; val x0 = offsetX; val y0 = offsetY
        val target = if (s <= 1f) Offset.Zero else {
            val c = Offset(size.width / 2f, size.height / 2f)
            val fc = (f ?: c) - c
            val rz = s / s0
            clamped(Offset(x0, y0) * rz + fc * (1f - rz), s)
        }
        zoomEvent++
        anim = scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                scale = s0 + (s - s0) * v
                offsetX = x0 + (target.x - x0) * v
                offsetY = y0 + (target.y - y0) * v
            }
        }
    }

    fun settle() {
        if (scale < 1f) animateTo(1f)
        else if (scale > 1f) {
            val c = clamped(Offset(offsetX, offsetY), scale)
            if (c.x != offsetX || c.y != offsetY) animateTo(scale)
        }
    }

    fun reset() { anim?.cancel(); scale = 1f; offsetX = 0f; offsetY = 0f }
}

@Composable
fun rememberZoomState(key: Any?): ZoomState {
    val scope = rememberCoroutineScope()
    return remember(key) { ZoomState(scope) }
}

/** Áp dụng phóng to/kéo đã tính cho nội dung. */
fun Modifier.zoomLayer(z: ZoomState): Modifier = graphicsLayer {
    scaleX = z.scale; scaleY = z.scale; translationX = z.offsetX; translationY = z.offsetY
}

/**
 * Cử chỉ:
 *  - 2 ngón: phóng to/thu nhỏ quanh tâm 2 ngón, kéo cùng lúc
 *  - chạm 2 lần: phóng to 2.5x tại chỗ chạm (có animation) / chạm 2 lần nữa để về như cũ
 *  - chạm 2 lần và giữ rồi kéo lên/xuống: phóng to/thu nhỏ bằng 1 ngón
 *  - 1 ngón khi đang phóng: kéo ảnh
 *  - chạm 1 lần: onTap (ẩn/hiện thanh công cụ)
 */
fun Modifier.zoomGestures(
    z: ZoomState,
    scope: CoroutineScope,
    onRotateEnd: ((Float) -> Unit)? = null,
    onTap: () -> Unit,
): Modifier = this
    .onSizeChanged { z.size = it }
    .pointerInput(z) {
        var lastUp = 0L
        var lastPos = Offset.Zero
        var tapJob: Job? = null
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val second = lastUp > 0 && down.uptimeMillis - lastUp < 300 && (down.position - lastPos).getDistance() < 100f
            if (second) {
                tapJob?.cancel()
                lastUp = 0
                down.consume()
                var dragging = false
                var prevY = down.position.y
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val dy = ch.position.y - down.position.y
                    if (!dragging && abs(dy) > slop) { dragging = true; prevY = ch.position.y }
                    if (dragging) {
                        val step = ch.position.y - prevY
                        prevY = ch.position.y
                        z.touching = true
                        z.zoomBy(exp(step / 200f), down.position, min = 1f)
                        ch.consume()
                    }
                }
                if (!dragging) {
                    if (z.zoomed) z.animateTo(1f) else z.animateTo(DOUBLE_TAP_ZOOM, down.position)
                } else z.settle()
                z.touching = false
                return@awaitEachGesture
            }

            var moved = false
            var multi = false
            var upTime = down.uptimeMillis
            var upConsumed = false
            var rotAcc = 0f
            var rotating = false
            do {
                val ev = awaitPointerEvent()
                upTime = ev.changes.first().uptimeMillis
                upConsumed = ev.changes.first().isConsumed
                val pressed = ev.changes.count { it.pressed }
                if (pressed > 1) multi = true
                if (multi && pressed > 1) {
                    z.touching = true
                    if (onRotateEnd != null) {
                        rotAcc += ev.calculateRotation()
                        // Xoay quá 15° mới tính là xoay, tránh lỡ tay khi chỉ muốn zoom
                        if (!rotating && abs(rotAcc) > 15f) rotating = true
                        if (rotating) z.freeRotation = rotAcc
                    }
                    z.zoomBy(ev.calculateZoom(), ev.calculateCentroid(useCurrent = true), ev.calculatePan())
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    moved = true
                } else if (z.zoomed && pressed == 1) {
                    val p = ev.calculatePan()
                    if (p != Offset.Zero) { z.panBy(p); moved = moved || p.getDistance() > 0.5f }
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (!moved) {
                    val ch = ev.changes.first()
                    if ((ch.position - down.position).getDistance() > slop) moved = true
                }
            } while (ev.changes.any { it.pressed })

            z.touching = false
            if (rotating) onRotateEnd?.invoke(z.freeRotation)
            if (multi || moved) {
                z.settle()
            } else if (!upConsumed && upTime - down.uptimeMillis < 500) {
                lastUp = upTime
                lastPos = down.position
                tapJob = scope.launch { delay(300); onTap() }
            }
        }
    }

package com.mondiro.mphoto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 1 nét vẽ. Toạ độ điểm chuẩn hoá 0..1 theo ảnh lúc vẽ; width = tỉ lệ so với chiều rộng ảnh. */
data class Stroke(val xs: FloatArray, val ys: FloatArray, val color: Int, val width: Float, val kind: Int) {
    companion object { const val PEN = 0; const val MARKER = 1; const val ERASER = 2 }
}

/** Các bước sửa ảnh, áp dụng lần lượt lên ảnh gốc (xem trước ở độ phân giải thấp, lưu ở độ phân giải cao). */
sealed interface EditOp {
    data class Rotate(val deg: Int) : EditOp
    data class Flip(val horizontal: Boolean) : EditOp
    data class Straighten(val deg: Float) : EditOp
    data class Crop(val l: Float, val t: Float, val r: Float, val b: Float) : EditOp
    data class Draw(val stroke: Stroke) : EditOp
}

object EditRender {
    /** Đọc ảnh (đã xoay đúng chiều theo EXIF), cạnh dài nhất tối đa [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val m = max(w, h)
            if (m > maxSide) d.setTargetSize((w.toLong() * maxSide / m).toInt().coerceAtLeast(1), (h.toLong() * maxSide / m).toInt().coerceAtLeast(1))
        }

    /** Hệ số phóng để ảnh xoay nghiêng [deg] độ vẫn phủ kín khung (không lộ góc trống). */
    fun coverScale(deg: Float, w: Float, h: Float): Float {
        val r = Math.toRadians(deg.toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        return max((w * c + h * s) / w, (w * s + h * c) / h)
    }

    fun applyAll(src: Bitmap, ops: List<EditOp>): Bitmap {
        var cur = src
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op is EditOp.Draw) {
                // Gộp các nét liền nhau vào 1 lớp để Tẩy chỉ xoá nét vẽ, không xoá ảnh
                val group = ArrayList<Stroke>()
                while (i < ops.size && ops[i] is EditOp.Draw) { group += (ops[i] as EditOp.Draw).stroke; i++ }
                cur = replace(cur, drawStrokes(cur, group), src)
                continue
            }
            cur = replace(cur, apply(cur, op), src)
            i++
        }
        return cur
    }

    private fun replace(old: Bitmap, new: Bitmap, keep: Bitmap): Bitmap {
        if (old !== new && old !== keep) old.recycle()
        return new
    }

    private fun apply(b: Bitmap, op: EditOp): Bitmap = when (op) {
        is EditOp.Rotate -> Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(op.deg.toFloat()) }, true)
        is EditOp.Flip -> Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { if (op.horizontal) postScale(-1f, 1f) else postScale(1f, -1f) }, true)
        is EditOp.Straighten -> {
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val s = coverScale(op.deg, b.width.toFloat(), b.height.toFloat())
            Canvas(out).apply {
                translate(b.width / 2f, b.height / 2f); rotate(op.deg); scale(s, s)
                drawBitmap(b, -b.width / 2f, -b.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            out
        }
        is EditOp.Crop -> {
            val l = (op.l * b.width).toInt().coerceIn(0, b.width - 1)
            val t = (op.t * b.height).toInt().coerceIn(0, b.height - 1)
            val w = ((op.r - op.l) * b.width).toInt().coerceIn(1, b.width - l)
            val h = ((op.b - op.t) * b.height).toInt().coerceIn(1, b.height - t)
            Bitmap.createBitmap(b, l, t, w, h)
        }
        is EditOp.Draw -> drawStrokes(b, listOf(op.stroke))
    }

    fun drawStrokes(b: Bitmap, strokes: List<Stroke>): Bitmap {
        val w = b.width; val h = b.height
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        for (s in strokes) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = s.color
                strokeWidth = s.width * w * (if (s.kind == Stroke.MARKER) 2.2f else 1f)
                if (s.kind == Stroke.MARKER) alpha = 102
                if (s.kind == Stroke.ERASER) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val path = Path()
            for (k in s.xs.indices) {
                val x = s.xs[k] * w; val y = s.ys[k] * h
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (s.xs.size == 1) path.lineTo(s.xs[0] * w + 0.1f, s.ys[0] * h)
            lc.drawPath(path, p)
        }
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        return out
    }
}

package kr.fairpool.car.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws the course markers as bitmaps:
 * pickup = filled circle, drop-off = pin, driver start/end = rounded label.
 */
object MarkerIcons {

    const val GREEN = 0xFF1FA463.toInt()
    const val BLUE = 0xFF3478F6.toInt()
    const val DARK = 0xFF1B2233.toInt()

    fun circle(label: String, fill: Int, density: Float): Bitmap {
        val pad = 4 * density
        val d = 40 * density
        val bmp = Bitmap.createBitmap((d + pad * 2).toInt(), (d + pad * 2).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f
        c.drawCircle(cx, cy, d / 2, shapePaint(Color.WHITE, density))
        c.drawCircle(cx, cy, d / 2 - 3 * density, shapePaint(fill, 0f))
        drawLabel(c, label, cx, cy, 17 * density)
        return bmp
    }

    fun pin(label: String, fill: Int, density: Float): Bitmap {
        val pad = 4 * density
        val w = 40 * density
        val h = 52 * density
        val bmp = Bitmap.createBitmap((w + pad * 2).toInt(), (h + pad * 2).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = bmp.width / 2f
        val r = w / 2
        c.drawPath(pinPath(cx, pad + r, r, pad + h), shapePaint(Color.WHITE, density))
        val stroke = 3 * density
        c.drawPath(pinPath(cx, pad + r, r - stroke, pad + h - stroke * 1.8f), shapePaint(fill, 0f))
        drawLabel(c, label, cx, pad + r, 17 * density)
        return bmp
    }

    fun tag(label: String, fill: Int, density: Float): Bitmap {
        val pad = 4 * density
        val textPaint = labelPaint(15 * density)
        val w = textPaint.measureText(label) + 24 * density
        val h = 32 * density
        val bmp = Bitmap.createBitmap((w + pad * 2).toInt(), (h + pad * 2).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val outer = RectF(pad, pad, pad + w, pad + h)
        c.drawRoundRect(outer, h / 2, h / 2, shapePaint(Color.WHITE, density))
        val inset = 2.5f * density
        val inner = RectF(outer.left + inset, outer.top + inset, outer.right - inset, outer.bottom - inset)
        c.drawRoundRect(inner, h / 2, h / 2, shapePaint(fill, 0f))
        drawLabel(c, label, bmp.width / 2f, bmp.height / 2f, 15 * density)
        return bmp
    }

    private fun pinPath(cx: Float, cy: Float, r: Float, tipY: Float): Path {
        val head = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val tail = Path().apply {
            moveTo(cx - r * 0.62f, cy + r * 0.55f)
            lineTo(cx, tipY)
            lineTo(cx + r * 0.62f, cy + r * 0.55f)
            close()
        }
        head.op(tail, Path.Op.UNION)
        return head
    }

    private fun shapePaint(color: Int, shadow: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (shadow > 0f) setShadowLayer(shadow * 2, 0f, shadow, 0x40000000)
    }

    private fun labelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private fun drawLabel(c: Canvas, label: String, cx: Float, cy: Float, size: Float) {
        val p = labelPaint(size)
        c.drawText(label, cx, cy - (p.descent() + p.ascent()) / 2, p)
    }
}

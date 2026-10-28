package com.demo.swarhands.games.signs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot

/**
 * Draws each [Sign] from plain shapes (no bitmaps): a cartoon hand palm facing you like your
 * own mirrored hand on screen, two hands for 6–10, a heart, or the outline of a shape to draw.
 * Each hand part gets a dark outline then its fill, back to front: fingers, palm, thumb.
 * Paints, paths and rects are reused.
 */
class SignPainter {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()
    private val path = Path()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Any sign, about [size] tall at (cx, cy). For shapes, [trace] in 0..1 moves a pointing hand
     * along the outline to show how to draw it (negative = no hand).
     */
    fun icon(c: Canvas, sign: Sign, cx: Float, cy: Float, size: Float, alpha: Int = 255, trace: Float = -1f) {
        when (sign.kind) {
            SignKind.HAND -> draw(c, sign, cx, cy, size, alpha)
            SignKind.TWO_HANDS -> if (sign == Sign.HEART) heart(c, cx, cy, size, alpha) else {
                val k = size * 0.62f
                draw(c, Sign.FIVE, cx - size * 0.24f, cy, k, alpha)
                Sign.fingers(sign.number - 5)?.let { draw(c, it, cx + size * 0.24f, cy, k, alpha) }
            }
            SignKind.SHAPE -> shape(c, sign, cx, cy, size, alpha, trace)
        }
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
        val k = size * 0.42f
        path.reset()
        path.moveTo(cx, cy + k * 0.9f)
        path.cubicTo(cx - k * 1.35f, cy + k * 0.05f, cx - k * 0.95f, cy - k * 1.05f, cx, cy - k * 0.4f)
        path.cubicTo(cx + k * 0.95f, cy - k * 1.05f, cx + k * 1.35f, cy + k * 0.05f, cx, cy + k * 0.9f)
        path.close()
        fill.color = HEART
        fill.alpha = alpha
        c.drawPath(path, fill)
        line.color = OUTLINE
        line.alpha = alpha
        line.strokeWidth = size * 0.035f
        c.drawPath(path, line)
        // two thumbs meeting at the bottom, two fingers at the top
        fill.color = SKIN
        fill.alpha = alpha
        for (side in intArrayOf(-1, 1)) {
            c.drawCircle(cx + side * k * 0.22f, cy + k * 0.98f, k * 0.17f, fill)
            c.drawCircle(cx + side * k * 0.22f, cy + k * 0.98f, k * 0.17f, line)
            c.drawCircle(cx + side * k * 0.2f, cy - k * 0.5f, k * 0.17f, fill)
            c.drawCircle(cx + side * k * 0.2f, cy - k * 0.5f, k * 0.17f, line)
        }
    }

    private fun shape(c: Canvas, sign: Sign, cx: Float, cy: Float, size: Float, alpha: Int, trace: Float) {
        val pts = ShapeRecognizer.outline(sign) ?: return
        val k = size * 0.33f
        path.reset()
        path.moveTo(cx + pts[0] * k, cy + pts[1] * k)
        for (i in 1 until pts.size / 2) path.lineTo(cx + pts[i * 2] * k, cy + pts[i * 2 + 1] * k)
        pen.color = OUTLINE
        pen.alpha = alpha
        pen.strokeWidth = size * 0.1f
        c.drawPath(path, pen)
        pen.color = SHAPE_BLUE
        pen.alpha = alpha
        pen.strokeWidth = size * 0.065f
        c.drawPath(path, pen)
        if (trace < 0f) return

        // the point [trace] of the way along the outline
        var total = 0f
        for (i in 1 until pts.size / 2) total += hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1])
        var want = total * (trace % 1f)
        var px = pts[0]
        var py = pts[1]
        for (i in 1 until pts.size / 2) {
            val seg = hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1])
            if (want <= seg && seg > 0f) {
                val f = want / seg
                px = pts[i * 2 - 2] + (pts[i * 2] - pts[i * 2 - 2]) * f
                py = pts[i * 2 - 1] + (pts[i * 2 + 1] - pts[i * 2 - 1]) * f
                break
            }
            want -= seg
        }
        val dx = cx + px * k
        val dy = cy + py * k
        fill.color = Color.WHITE
        fill.alpha = alpha
        c.drawCircle(dx, dy, size * 0.05f, fill)
        // pointing hand with its fingertip on the dot
        val hs = size * 0.42f
        draw(c, Sign.ONE, dx + 0.21f * hs, dy + 0.48f * hs, hs, alpha)
    }
    private var s = 1f
    private var stroke = 1f

    /** Hand centred at (cx, cy), about [size] tall. */
    fun draw(c: Canvas, sign: Sign, cx: Float, cy: Float, size: Float, alpha: Int = 255, skin: Int = SKIN) {
        s = size
        stroke = size * 0.035f
        fill.color = skin
        fill.alpha = alpha
        line.color = OUTLINE
        line.alpha = alpha
        line.strokeWidth = stroke
        c.save()
        c.translate(cx, cy)
        when (sign) {
            Sign.THUMBS_UP -> thumbFist(c)
            Sign.THUMBS_DOWN -> {
                c.rotate(180f)
                thumbFist(c)
            }
            Sign.FIST -> {
                for (i in 0..3) knuckle(c, i)
                palm(c)
                foldedThumb(c)
            }
            Sign.OK -> {
                for (i in 1..3) finger(c, i)
                palm(c)
                // thumb and index meet in a ring
                line.strokeWidth = s * 0.1f + stroke * 2f
                line.color = OUTLINE
                c.drawCircle(-0.25f * s, -0.16f * s, 0.12f * s, line)
                line.strokeWidth = s * 0.1f
                line.color = fill.color
                c.drawCircle(-0.25f * s, -0.16f * s, 0.12f * s, line)
                line.color = OUTLINE
                line.strokeWidth = stroke
            }
            else -> {
                val up = UP[sign.ordinal]
                for (i in 0..3) if (up and (1 shl i) != 0) finger(c, i) else knuckle(c, i)
                palm(c)
                if (up and THUMB != 0) openThumb(c) else foldedThumb(c)
            }
        }
        c.restore()
    }

    private fun shape(c: Canvas, l: Float, t: Float, rr: Float, b: Float, radius: Float) {
        r.set(l * s, t * s, rr * s, b * s)
        c.drawRoundRect(r, radius * s, radius * s, fill)
        c.drawRoundRect(r, radius * s, radius * s, line)
    }

    private fun palm(c: Canvas) = shape(c, -0.3f, -0.12f, 0.3f, 0.38f, 0.13f)

    private fun finger(c: Canvas, i: Int) {
        val x = FX[i]
        shape(c, x - 0.065f, -0.1f - FLEN[i], x + 0.065f, 0f, 0.065f)
    }

    /** A curled finger: just the knuckle peeking over the palm. */
    private fun knuckle(c: Canvas, i: Int) {
        val x = FX[i]
        shape(c, x - 0.068f, -0.2f, x + 0.068f, -0.04f, 0.068f)
    }

    private fun openThumb(c: Canvas) {
        c.save()
        c.rotate(-48f, -0.26f * s, 0.16f * s)
        shape(c, -0.335f, -0.2f, -0.185f, 0.18f, 0.075f)
        c.restore()
    }

    private fun foldedThumb(c: Canvas) = shape(c, -0.32f, 0.04f, 0.06f, 0.17f, 0.065f)

    /** 👍: a fist seen from the side with the thumb standing up. */
    private fun thumbFist(c: Canvas) {
        shape(c, -0.24f, -0.42f, -0.08f, 0.04f, 0.08f)   // thumb (behind the fist)
        shape(c, -0.26f, -0.06f, 0.24f, 0.38f, 0.12f)     // fist
        for (k in 0..3) {                                  // curled fingers
            val y = -0.02f + k * 0.098f
            shape(c, -0.04f, y, 0.3f, y + 0.09f, 0.045f)
        }
    }

    companion object {
        val SKIN = Color.parseColor("#FFCC80")
        val HEART = Color.parseColor("#FF4F81")
        val SHAPE_BLUE = Color.parseColor("#29B6F6")
        val OUTLINE = Color.parseColor("#5D4037")
        private const val THUMB = 1 shl 4
        private val FX = floatArrayOf(-0.21f, -0.07f, 0.07f, 0.21f)
        private val FLEN = floatArrayOf(0.38f, 0.44f, 0.40f, 0.31f)

        /** Fingers up per sign: bits 0-3 index..pinky, bit 4 thumb out (index = Sign.ordinal). */
        private val UP = IntArray(Sign.entries.size).also {
            it[Sign.ONE.ordinal] = 0b00001
            it[Sign.TWO.ordinal] = 0b00011
            it[Sign.THREE.ordinal] = 0b00111
            it[Sign.FOUR.ordinal] = 0b01111
            it[Sign.FIVE.ordinal] = 0b11111
            it[Sign.ROCK.ordinal] = 0b01001
            it[Sign.CALL.ordinal] = 0b11000
        }
    }
}

package com.demo.swarhands.games.bullseye

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/**
 * Draws the slingshot, the bow and arrows with plain shapes (no bitmaps).
 * Shared by the game view and the weapon picker icons. Paints and paths are reused.
 */
class WeaponPainter(private val dp: Float) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    // ---------------------------------------------------------------- slingshot

    /**
     * Fork centred at (ax, ay), [s] = scale (fork height). The pouch sits at (px, py).
     * [loaded] draws a stone in the pouch; [over] turns the bands red-hot.
     */
    fun slingshot(c: Canvas, ax: Float, ay: Float, s: Float, px: Float, py: Float, power: Float, loaded: Boolean, over: Boolean, now: Long) {
        val baseY = ay + s * 0.3f
        val tipLx = ax - s * 0.55f
        val tipRx = ax + s * 0.55f
        val tipY = ay - s * 0.45f

        // handle
        layered(c, s * 0.26f) { c.drawLine(ax, baseY, ax, ay + s * 1.5f, stroke) }

        // fork
        path.reset()
        path.moveTo(tipLx, tipY)
        path.quadTo(ax - s * 0.12f, ay + s * 0.15f, ax, baseY)
        path.quadTo(ax + s * 0.12f, ay + s * 0.15f, tipRx, tipY)
        layered(c, s * 0.2f) { c.drawPath(path, stroke) }

        // bands: thinner the further they stretch
        val bandW = (s * 0.09f * (1f - 0.45f * min(power, 1.2f))).coerceAtLeast(dp * 2f)
        stroke.strokeWidth = bandW
        stroke.color = if (over) (if ((now / 90) % 2 == 0L) HOT_BAND else HOT_BAND_2) else BAND
        c.drawLine(tipLx, tipY + s * 0.04f, px - s * 0.17f, py, stroke)
        c.drawLine(tipRx, tipY + s * 0.04f, px + s * 0.17f, py, stroke)

        // leather wraps on the tips
        fill.color = LEATHER
        c.drawCircle(tipLx, tipY + s * 0.04f, s * 0.11f, fill)
        c.drawCircle(tipRx, tipY + s * 0.04f, s * 0.11f, fill)

        // pouch + stone
        oval.set(px - s * 0.24f, py - s * 0.12f, px + s * 0.24f, py + s * 0.14f)
        fill.color = LEATHER
        c.drawOval(oval, fill)
        if (loaded) stone(c, px, py - s * 0.03f, s * 0.17f)
    }

    fun stone(c: Canvas, x: Float, y: Float, r: Float) {
        fill.color = STONE_DARK
        c.drawCircle(x, y, r, fill)
        fill.color = STONE
        c.drawCircle(x - r * 0.08f, y - r * 0.08f, r * 0.86f, fill)
        fill.color = STONE_LIGHT
        c.drawCircle(x - r * 0.32f, y - r * 0.32f, r * 0.3f, fill)
    }

    // ---------------------------------------------------------------- bow

    /**
     * Bow held at (gx, gy) aiming along unit (dx, dy). [l] = half the bow's length.
     * When [drawn], the string is pulled to (nx, ny) with an arrow on it.
     */
    fun bow(c: Canvas, gx: Float, gy: Float, dx: Float, dy: Float, l: Float, drawn: Boolean, nx: Float, ny: Float, power: Float) {
        val px = -dy // perpendicular
        val py = dx
        val bend = l * (0.28f + 0.2f * min(power, 1f))
        val aX = gx + px * l - dx * bend
        val aY = gy + py * l - dy * bend
        val bX = gx - px * l - dx * bend
        val bY = gy - py * l - dy * bend

        // string first, so the limbs sit on top of it
        stroke.strokeWidth = dp * 1.6f
        stroke.color = STRING
        if (drawn) {
            c.drawLine(aX, aY, nx, ny, stroke)
            c.drawLine(nx, ny, bX, bY, stroke)
        } else {
            c.drawLine(aX, aY, bX, bY, stroke)
        }

        // limbs: a curve through the grip
        path.reset()
        path.moveTo(aX, aY)
        path.quadTo(gx + dx * bend, gy + dy * bend, bX, bY)
        stroke.strokeWidth = l * 0.13f + dp * 3f
        stroke.color = OUTLINE
        c.drawPath(path, stroke)
        stroke.strokeWidth = l * 0.13f
        stroke.color = BOW_WOOD
        c.drawPath(path, stroke)
        stroke.strokeWidth = l * 0.035f
        stroke.color = BOW_LIGHT
        c.drawPath(path, stroke)

        // grip wrap
        stroke.strokeWidth = l * 0.17f
        stroke.color = GRIP
        c.drawLine(gx + px * l * 0.14f, gy + py * l * 0.14f, gx - px * l * 0.14f, gy - py * l * 0.14f, stroke)

        if (drawn) {
            val headX = gx + dx * l * 0.55f
            val headY = gy + dy * l * 0.55f
            arrow(c, nx, ny, headX, headY, l * 0.09f, 255)
        }
    }

    /** Arrow from its tail (tx, ty) to its tip (hx, hy). [k] scales head and fletching. */
    fun arrow(c: Canvas, tx: Float, ty: Float, hx: Float, hy: Float, k: Float, alpha: Int) {
        val vx = hx - tx
        val vy = hy - ty
        val len = kotlin.math.hypot(vx, vy).coerceAtLeast(0.01f)
        val ux = vx / len
        val uy = vy / len
        val px = -uy
        val py = ux

        // fletching
        fill.color = FLETCH
        fill.alpha = alpha
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            path.reset()
            path.moveTo(tx + ux * k * 0.4f, ty + uy * k * 0.4f)
            path.lineTo(tx + ux * k * 2.6f, ty + uy * k * 2.6f)
            path.lineTo(tx - ux * k * 0.3f + px * k * sgn, ty - uy * k * 0.3f + py * k * sgn)
            path.close()
            c.drawPath(path, fill)
        }

        // shaft
        stroke.strokeWidth = (k * 0.45f).coerceAtLeast(dp * 0.8f)
        stroke.color = SHAFT
        stroke.alpha = alpha
        c.drawLine(tx, ty, hx - ux * k * 1.2f, hy - uy * k * 1.2f, stroke)

        // head
        fill.color = HEAD
        fill.alpha = alpha
        path.reset()
        path.moveTo(hx, hy)
        path.lineTo(hx - ux * k * 1.8f + px * k * 0.7f, hy - uy * k * 1.8f + py * k * 0.7f)
        path.lineTo(hx - ux * k * 1.8f - px * k * 0.7f, hy - uy * k * 1.8f - py * k * 0.7f)
        path.close()
        c.drawPath(path, fill)
        fill.alpha = 255
        stroke.alpha = 255
    }

    /** Dark outline, wood, then a thin highlight, all with the current [stroke] geometry. */
    private inline fun layered(c: Canvas, width: Float, draw: () -> Unit) {
        stroke.strokeWidth = width + dp * 3f
        stroke.color = OUTLINE
        draw()
        stroke.strokeWidth = width
        stroke.color = WOOD
        draw()
        stroke.strokeWidth = width * 0.3f
        stroke.color = WOOD_LIGHT
        draw()
    }

    private companion object {
        val OUTLINE = Color.parseColor("#3B2412")
        val WOOD = Color.parseColor("#8D5A2B")
        val WOOD_LIGHT = Color.parseColor("#B98250")
        val LEATHER = Color.parseColor("#4E342E")
        val BAND = Color.parseColor("#C0392B")
        val HOT_BAND = Color.parseColor("#FF1744")
        val HOT_BAND_2 = Color.parseColor("#FF8A65")
        val STONE = Color.parseColor("#9E9E9E")
        val STONE_DARK = Color.parseColor("#424242")
        val STONE_LIGHT = Color.parseColor("#E0E0E0")
        val BOW_WOOD = Color.parseColor("#7B3F1D")
        val BOW_LIGHT = Color.parseColor("#C27C4E")
        val GRIP = Color.parseColor("#1F140D")
        val STRING = Color.parseColor("#F5F5F5")
        val SHAFT = Color.parseColor("#D7B98E")
        val FLETCH = Color.parseColor("#FF5252")
        val HEAD = Color.parseColor("#CFD8DC")
    }
}

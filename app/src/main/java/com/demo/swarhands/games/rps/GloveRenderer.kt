package com.demo.swarhands.games.rps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * Draws the AI's cartoon white glove for each move.
 * Rendered ONCE into bitmaps at startup, so each frame is just a cheap bitmap blit (good for low-end phones).
 * The glove points DOWN toward the player, like an opponent across the table.
 */
object GloveRenderer {
    private const val UNIT_W = 120f
    private const val UNIT_H = 125f
    private val tmp = RectF()

    fun render(move: RpsMove, widthPx: Int): Bitmap {
        val s = widthPx / UNIT_W
        val bmp = Bitmap.createBitmap(widthPx, (UNIT_H * s).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        c.rotate(180f, UNIT_W / 2f, UNIT_H / 2f)
        c.translate(10f, 0f)

        // soft drop shadow (offset copy), appears down-right on screen
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        c.save()
        c.translate(-2.5f, -3.5f)
        drawShapes(c, move, shadow, null, null)
        c.restore()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 100f, 125f, Color.WHITE, Color.rgb(212, 216, 228), Shader.TileMode.CLAMP)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(30, 30, 42)
        }
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(120, 124, 140)
        }
        drawShapes(c, move, fill, outline, detail)
        return bmp
    }

    /** Each part is filled then outlined; later parts cover earlier ones, giving clean cartoon overlaps. */
    private fun drawShapes(c: Canvas, move: RpsMove, fill: Paint, outline: Paint?, detail: Paint?) {
        when (move) {
            RpsMove.PAPER -> {
                finger(c, 24f, 80f, 16f, 27f, -50f, fill, outline)   // thumb out
                finger(c, 31f, 56f, 15f, 40f, -14f, fill, outline)   // index
                finger(c, 45f, 54f, 16f, 46f, -4f, fill, outline)    // middle
                finger(c, 59f, 55f, 15f, 42f, 6f, fill, outline)     // ring
                finger(c, 71f, 60f, 13f, 32f, 18f, fill, outline)    // pinky
                palmAndCuff(c, fill, outline, detail)
            }
            RpsMove.SCISSORS -> {
                finger(c, 59f, 54f, 15f, 18f, 2f, fill, outline)     // ring knuckle
                finger(c, 71f, 58f, 13f, 15f, 6f, fill, outline)     // pinky knuckle
                finger(c, 32f, 56f, 15f, 44f, -18f, fill, outline)   // index (V)
                finger(c, 47f, 54f, 16f, 46f, 14f, fill, outline)    // middle (V)
                palmAndCuff(c, fill, outline, detail)
                finger(c, 34f, 84f, 16f, 24f, 72f, fill, outline)    // thumb tucked across palm
            }
            RpsMove.ROCK -> {
                finger(c, 31f, 54f, 15f, 18f, -6f, fill, outline)    // knuckles
                finger(c, 45f, 52f, 16f, 20f, -1f, fill, outline)
                finger(c, 59f, 53f, 15f, 19f, 4f, fill, outline)
                finger(c, 71f, 57f, 13f, 16f, 9f, fill, outline)
                palmAndCuff(c, fill, outline, detail)
                finger(c, 34f, 84f, 16f, 24f, 72f, fill, outline)    // thumb wrapped over the fist
            }
        }
    }

    private fun palmAndCuff(c: Canvas, fill: Paint, outline: Paint?, detail: Paint?) {
        part(c, 20f, 48f, 80f, 98f, 18f, fill, outline)  // palm
        part(c, 18f, 96f, 82f, 122f, 8f, fill, outline)  // rolled cuff
        if (detail != null) {
            // classic cartoon-glove stitching on the back of the hand
            c.drawLine(38f, 64f, 37f, 84f, detail)
            c.drawLine(50f, 62f, 50f, 84f, detail)
            c.drawLine(62f, 64f, 63f, 84f, detail)
            // cuff ribs
            c.drawLine(28f, 104f, 72f, 104f, detail)
            c.drawLine(28f, 113f, 72f, 113f, detail)
        }
    }

    private fun finger(
        c: Canvas, baseX: Float, baseY: Float, width: Float, length: Float, angle: Float,
        fill: Paint, outline: Paint?
    ) {
        c.save()
        c.rotate(angle, baseX, baseY)
        part(c, baseX - width / 2f, baseY - length, baseX + width / 2f, baseY + 10f, width / 2f, fill, outline)
        c.restore()
    }

    private fun part(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, fill: Paint, outline: Paint?) {
        tmp.set(l, t, r, b)
        c.drawRoundRect(tmp, radius, radius, fill)
        if (outline != null) c.drawRoundRect(tmp, radius, radius, outline)
    }
}

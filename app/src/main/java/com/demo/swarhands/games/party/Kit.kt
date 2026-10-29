package com.demo.swarhands.games.party

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.demo.swarhands.games.signs.SignPainter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A line of text in English and in Hinglish. */
class Words(val en: String, val hi: String) {
    fun get(hinglish: Boolean) = if (hinglish) hi else en
}

/**
 * Shared drawing tools for the party screen and every microgame: text with an outline,
 * emoji, a few colours and a small particle pool for bursts. Created once by the view;
 * nothing here allocates while drawing.
 */
class Kit(val dp: Float, private val spScale: Float) {
    fun dp(v: Float) = v * dp
    fun sp(v: Float) = v * spScale

    var w = 1f
    var h = 1f
    var hinglish = false

    val painter = SignPainter()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val rect = RectF()
    private val heavy = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = heavy
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = heavy
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Centered text with a dark outline so it reads over the camera. Shrinks to fit the screen. */
    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        alpha: Int = 255, scale: Float = 1f, outline: Boolean = true
    ) {
        if (alpha <= 0 || scale <= 0f || s.isEmpty()) return
        textPaint.textSize = size
        val fit = min(1f, (w - dp(28f)) / textPaint.measureText(s))
        val sz = size * fit
        textPaint.textSize = sz
        textPaint.color = color
        textPaint.alpha = alpha.coerceAtMost(255)
        val off = (textPaint.descent() + textPaint.ascent()) / 2f
        if (scale != 1f) {
            c.save()
            c.scale(scale, scale, x, y)
        }
        if (outline) {
            outlinePaint.textSize = sz
            outlinePaint.strokeWidth = sz * 0.13f
            outlinePaint.color = Color.BLACK
            outlinePaint.alpha = alpha.coerceAtMost(255) * 160 / 255
            c.drawText(s, x, y - off, outlinePaint)
        }
        c.drawText(s, x, y - off, textPaint)
        if (scale != 1f) c.restore()
    }

    /** An emoji centred at (x, y), about [size] tall. */
    fun emoji(c: Canvas, e: String, x: Float, y: Float, size: Float, alpha: Int = 255, rotate: Float = 0f) {
        emojiPaint.textSize = size
        emojiPaint.alpha = alpha
        val off = (emojiPaint.descent() + emojiPaint.ascent()) / 2f
        if (rotate != 0f) {
            c.save()
            c.rotate(rotate, x, y)
        }
        c.drawText(e, x, y - off, emojiPaint)
        if (rotate != 0f) c.restore()
    }

    // ---------- particles ----------
    private val n = 60
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val life = FloatArray(n)
    private val color = IntArray(n)
    private var next = 0
    var particlesActive = false
        private set

    fun burst(x: Float, y: Float, count: Int, colors: IntArray) {
        for (k in 0 until count) {
            val i = next
            next = (next + 1) % n
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val sp = dp(80f) + Random.nextFloat() * dp(240f)
            px[i] = x
            py[i] = y
            vx[i] = cos(a) * sp
            vy[i] = sin(a) * sp - dp(80f)
            life[i] = 0.5f + Random.nextFloat() * 0.4f
            color[i] = colors[k % colors.size]
        }
        particlesActive = true
    }

    fun drawParticles(c: Canvas, dt: Float) {
        var alive = false
        for (i in 0 until n) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            vy[i] += dp(650f) * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            if (life[i] <= 0f) continue
            alive = true
            fill.color = color[i]
            fill.alpha = (255 * min(1f, life[i] / 0.3f)).toInt()
            c.drawCircle(px[i], py[i], dp(3.5f), fill)
        }
        particlesActive = alive
    }

    fun clearParticles() {
        life.fill(0f)
        particlesActive = false
    }

    companion object {
        val GOLD = Color.parseColor("#FFD54F")
        val GREEN = Color.parseColor("#69F0AE")
        val RED = Color.parseColor("#FF5252")
        val BLUE = Color.parseColor("#4FC3F7")
        val PINK = Color.parseColor("#FF4F81")
        val MUTED = Color.parseColor("#B0B3C6")
        val SOFT_WHITE = Color.parseColor("#E6FFFFFF")
        val PARTY = intArrayOf(GOLD, GREEN, BLUE, PINK, Color.WHITE)
    }
}

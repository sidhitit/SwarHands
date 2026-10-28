package com.demo.swarhands.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.demo.swarhands.gesture.AirKeyboard
import com.demo.swarhands.gesture.Finger
import com.demo.swarhands.gesture.FrameUi
import com.demo.swarhands.gesture.HandSkeleton
import com.demo.swarhands.gesture.HandUi
import com.demo.swarhands.gesture.NoteMap
import com.demo.swarhands.gesture.PlayMode
import com.demo.swarhands.gesture.Swar
import com.demo.swarhands.gesture.ViewTransform

/** Draws the hand skeleton, swar labels, air keys and the note flash on top of the camera. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    @Volatile private var frame: FrameUi? = null
    @Volatile private var flashSwar: Swar? = null
    @Volatile private var flashAt = 0L

    @Volatile var mode: PlayMode = PlayMode.PINCH
        set(value) {
            field = value
            postInvalidate()
        }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tmpRect = RectF()

    /** Thread-safe: called from the tracking thread. */
    fun transformFor(imgW: Int, imgH: Int): ViewTransform? {
        val w = width
        val h = height
        if (w == 0 || h == 0) return null
        return ViewTransform(w, h, imgW, imgH)
    }

    /** Thread-safe: called from the tracking thread. */
    fun submit(f: FrameUi) {
        frame = f
        f.played?.let {
            flashSwar = it
            flashAt = SystemClock.uptimeMillis()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame
        val m = mode
        if (m == PlayMode.AIR_KEYS) drawKeys(canvas, f?.activeKeys ?: emptySet())
        f?.hands?.forEach { drawHand(canvas, it, m) }
        drawFlash(canvas)
    }

    private fun drawKeys(c: Canvas, active: Set<Int>) {
        for (i in 0 until AirKeyboard.KEY_COUNT) {
            val swar = Swar.entries[i]
            tmpRect.set(AirKeyboard.keyRect(i, width, height))
            tmpRect.inset(dp(3f), 0f)
            val on = i in active
            fillPaint.color = swar.color
            fillPaint.alpha = if (on) 230 else 70
            c.drawRoundRect(tmpRect, dp(10f), dp(10f), fillPaint)
            ringPaint.alpha = if (on) 255 else 120
            c.drawRoundRect(tmpRect, dp(10f), dp(10f), ringPaint)
            drawCentered(c, swar.hindi, tmpRect.centerX(), tmpRect.centerY() - dp(10f), dp(22f))
            drawCentered(c, swar.latin, tmpRect.centerX(), tmpRect.centerY() + dp(16f), dp(12f))
        }
        ringPaint.alpha = 255
    }

    private fun drawHand(c: Canvas, hand: HandUi, m: PlayMode) {
        val pts = hand.points
        bonePaint.color = if (hand.side == "Left") LEFT_COLOR else RIGHT_COLOR
        for ((a, b) in HandSkeleton.BONES) {
            c.drawLine(pts[a].x, pts[a].y, pts[b].x, pts[b].y, bonePaint)
        }
        for (p in pts) c.drawCircle(p.x, p.y, dp(4.5f), jointPaint)

        val wrist = pts[HandSkeleton.WRIST]
        drawCentered(c, if (hand.side == "Left") "L" else "R", wrist.x, wrist.y + dp(18f), dp(14f), bonePaint.color)

        when (m) {
            PlayMode.PINCH -> {
                val thumb = pts[HandSkeleton.THUMB_TIP]
                c.drawCircle(thumb.x, thumb.y, dp(12f), ringPaint)
                for (finger in Finger.entries) {
                    val tip = pts[finger.tipIndex]
                    val swar = NoteMap.swarFor(hand.side, finger)
                    val on = finger in hand.pinched
                    fillPaint.color = swar.color
                    fillPaint.alpha = if (on) 240 else 110
                    val r = if (on) dp(22f) else dp(16f)
                    c.drawCircle(tip.x, tip.y, r, fillPaint)
                    c.drawCircle(tip.x, tip.y, r, ringPaint)
                    drawCentered(c, swar.hindi, tip.x, tip.y, if (on) dp(16f) else dp(13f))
                }
            }
            PlayMode.AIR_KEYS -> {
                val tip = pts[Finger.INDEX.tipIndex]
                fillPaint.color = Color.YELLOW
                fillPaint.alpha = 220
                c.drawCircle(tip.x, tip.y, dp(11f), fillPaint)
                c.drawCircle(tip.x, tip.y, dp(11f), ringPaint)
            }
        }
    }

    private fun drawFlash(c: Canvas) {
        val swar = flashSwar ?: return
        val elapsed = SystemClock.uptimeMillis() - flashAt
        if (elapsed >= FLASH_MS) return
        val alpha = ((1f - elapsed / FLASH_MS.toFloat()) * 255).toInt()
        val cx = width / 2f
        val cy = height * 0.5f
        drawCentered(c, swar.hindi, cx, cy, dp(90f), swar.color, alpha)
        drawCentered(c, swar.latin, cx, cy + dp(68f), dp(26f), Color.WHITE, alpha)
        postInvalidateOnAnimation() // keep fading even if frames pause
    }

    private fun drawCentered(
        c: Canvas, text: String, x: Float, y: Float, size: Float,
        color: Int = Color.WHITE, alpha: Int = 255
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.alpha = alpha
        textPaint.setShadowLayer(dp(3f), 0f, 0f, Color.argb(alpha * 3 / 4, 0, 0, 0))
        val offset = (textPaint.descent() + textPaint.ascent()) / 2f
        c.drawText(text, x, y - offset, textPaint)
    }

    private companion object {
        const val FLASH_MS = 800L
        val LEFT_COLOR = Color.parseColor("#4FC3F7")
        val RIGHT_COLOR = Color.parseColor("#FFB74D")
    }
}

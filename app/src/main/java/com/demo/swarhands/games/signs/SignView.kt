package com.demo.swarhands.games.signs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import com.demo.swarhands.audio.Sfx
import com.demo.swarhands.audio.SfxPlayer
import com.demo.swarhands.games.signs.SignGame.Phase
import com.demo.swarhands.gesture.HandSkeleton
import com.demo.swarhands.gesture.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws Sign Says over the camera preview: HUD, the sequence slots, the big sign cards while
 * you watch, and your own hand plus a hold ring while you repeat.
 * Paints, particles and strings are allocated once, never inside onDraw.
 */
class SignView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SignGame.Listener {

    var sfx: SfxPlayer? = null
    var onPhaseChanged: ((Phase) -> Unit)? = null
    var game: SignGame? = null
        private set

    @Volatile private var state: SignState? = null

    // ---------- units ----------
    private val dpF = resources.displayMetrics.density
    private val spF = dpF * resources.configuration.fontScale.coerceAtMost(1.3f)
    private fun dp(v: Float) = v * dpF
    private fun sp(v: Float) = v * spF

    // ---------- paints ----------
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
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val topScrim = Paint()
    private val bottomScrim = Paint()
    private val rect = RectF()
    private val painter = SignPainter()

    private val barTop get() = dp(14f)
    private val barBottom get() = dp(90f)

    // ---------- confetti ----------
    private val confN = 80
    private val cX = FloatArray(confN)
    private val cY = FloatArray(confN)
    private val cVx = FloatArray(confN)
    private val cVy = FloatArray(confN)
    private val cRot = FloatArray(confN)
    private val cVr = FloatArray(confN)
    private val cSize = FloatArray(confN)
    private val cColor = IntArray(confN)
    private var confettiActive = false

    // ---------- cached strings ----------
    private var scoreStr = "0"
    private var scoreFor = -1
    private var roundStr = "1"
    private var roundFor = -1
    private var stepStr = ""
    private var stepFor = -1
    private var bonusStr = ""
    private var roundClearStr = ""
    private var endLine1 = ""
    private var endLine2 = ""
    private var secStr = ""
    private var secFor = -1
    private var lastFrameAt = 0L

    // =====================================================================
    // Public API
    // =====================================================================

    fun start(bestScore: Int, bestRound: Int) {
        game = SignGame(bestScore, bestRound, this)
        invalidate()
    }

    /** Thread-safe: called from the tracking thread. */
    fun transformFor(imgW: Int, imgH: Int): ViewTransform? {
        val w = width
        val h = height
        if (w == 0 || h == 0) return null
        return ViewTransform(w, h, imgW, imgH)
    }

    /** Thread-safe: called from the tracking thread. */
    fun submit(s: SignState) {
        state = s
        postInvalidate()
    }

    fun restartGame() {
        game?.restart(SystemClock.uptimeMillis())
        confettiActive = false
        invalidate()
    }

    // =====================================================================
    // SignGame.Listener (UI thread, from game.update)
    // =====================================================================

    override fun onSfx(sfx: Sfx, volume: Float, rate: Float) {
        this.sfx?.play(sfx, volume, rate)
    }

    override fun onHaptic(strong: Boolean) {
        performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onPhaseChanged(phase: Phase) {
        val g = game ?: return
        when (phase) {
            Phase.ROUND_CLEAR -> {
                roundClearStr = "ROUND ${g.round} CLEAR!"
                bonusStr = "+${g.lastBonus} bonus"
            }
            Phase.GAME_OVER -> {
                endLine1 = "Reached round ${g.round} · ${g.score} points"
                endLine2 = if (g.newBest) "NEW BEST!" else "Best: ${g.bestScore} · round ${g.bestRound}"
                if (g.newBest) spawnConfetti()
            }
            else -> Unit
        }
        post { onPhaseChanged?.invoke(phase) } // never touch other views mid-draw
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        topScrim.shader = LinearGradient(
            0f, 0f, 0f, h * 0.3f, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        bottomScrim.shader = LinearGradient(
            0f, h * 0.65f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val g = game ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else (now - lastFrameAt).coerceIn(1, 50) / 1000f
        lastFrameAt = now
        val s = state

        g.update(now, s)
        trackStroke(s, now)
        val phase = g.phase
        val t = now - g.phaseStart
        val w = width.toFloat()
        val h = height.toFloat()

        c.drawRect(0f, 0f, w, h * 0.3f, topScrim)
        c.drawRect(0f, h * 0.65f, w, h, bottomScrim)
        drawHand(c, s, phase)
        if (phase == Phase.INPUT) drawTrail(c, s, now)
        drawHud(c, g, w, now)
        drawSlots(c, g, w, now)

        when (phase) {
            Phase.INTRO -> drawIntro(c, t, w, h)
            Phase.SHOW -> drawShow(c, g, now, w, h)
            Phase.GO -> drawBig(c, "YOUR TURN!", GOLD, t, w, h * 0.45f, sp(46f))
            Phase.INPUT -> drawInput(c, g, s, now, w, h)
            Phase.ROUND_CLEAR -> {
                drawBig(c, roundClearStr, WIN_GREEN, t, w, h * 0.42f, sp(36f))
                text(c, bonusStr, w / 2f, h * 0.42f + dp(44f), sp(18f), GOLD, alphaIn(t, 200))
            }
            Phase.MISTAKE -> drawMistake(c, g, t, w, h)
            Phase.GAME_OVER -> drawGameOver(c, t, w, h)
        }
        if (confettiActive) drawConfetti(c, dt, h)
        if (phase != Phase.GAME_OVER || t < 3000 || confettiActive) postInvalidateOnAnimation()
    }

    // ---------- your hand ----------

    private fun drawHand(c: Canvas, s: SignState?, phase: Phase) {
        val hands = s?.hands ?: return
        val active = phase == Phase.INPUT
        for (pts in hands) {
            strokePaint.strokeWidth = dp(if (active) 3f else 2f)
            strokePaint.color = if (active) Color.argb(170, 255, 255, 255) else Color.argb(70, 255, 255, 255)
            for ((i, j) in HandSkeleton.BONES) c.drawLine(pts[i].x, pts[i].y, pts[j].x, pts[j].y, strokePaint)
            fillPaint.color = if (active) YOU else Color.argb(90, 79, 195, 247)
            for (k in TIP_IDS) c.drawCircle(pts[k].x, pts[k].y, dp(if (active) 5f else 3.5f), fillPaint)
        }
    }

    // ---------- drawing in the air ----------

    /** Keeps the last trail around after the drawing ends so it can flash green or red. */
    private fun trackStroke(s: SignState?, now: Long) {
        if (s == null) return
        val live = s.stroke
        if (s.drawing && live != null) {
            if (trail.size < live.size) trail = FloatArray(live.size * 2)
            System.arraycopy(live, 0, trail, 0, live.size)
            trailN = s.strokeCount
            trailEndAt = 0L
        }
        if (s.strokeSeq != trailSeq) {
            trailSeq = s.strokeSeq
            trailEndAt = now
            trailOk = s.strokeShape != null
        }
    }

    private fun drawTrail(c: Canvas, s: SignState?, now: Long) {
        val drawingNow = s?.drawing == true
        val since = now - trailEndAt
        if (!drawingNow && (trailEndAt == 0L || since > 700)) return
        if (trailN < 2) return
        val a = if (drawingNow) 255 else (255 * (1f - since / 700f)).toInt()
        val color = when {
            drawingNow -> YOU
            trailOk -> WIN_GREEN
            else -> LOSE_RED
        }
        trailPath.reset()
        trailPath.moveTo(trail[0], trail[1])
        for (i in 1 until trailN) trailPath.lineTo(trail[i * 2], trail[i * 2 + 1])
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = dp(16f)
        strokePaint.color = color
        strokePaint.alpha = a * 70 / 255
        c.drawPath(trailPath, strokePaint)
        strokePaint.strokeWidth = dp(6f)
        strokePaint.color = Color.WHITE
        strokePaint.alpha = a
        c.drawPath(trailPath, strokePaint)
        if (drawingNow) {
            fillPaint.color = Color.WHITE
            c.drawCircle(trail[trailN * 2 - 2], trail[trailN * 2 - 1], dp(8f), fillPaint)
        }
    }

    // ---------- HUD ----------

    private fun drawHud(c: Canvas, g: SignGame, w: Float, now: Long) {
        val top = barTop
        rect.set(dp(16f), top, w - dp(16f), barBottom)
        fillPaint.color = Color.argb(205, 14, 14, 24)
        c.drawRoundRect(rect, dp(22f), dp(22f), fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(rect, dp(22f), dp(22f), strokePaint)

        if (scoreFor != g.score) {
            scoreFor = g.score
            scoreStr = g.score.toString()
        }
        if (roundFor != g.round) {
            roundFor = g.round
            roundStr = g.round.toString()
        }
        text(c, "ROUND", w * 0.17f, top + dp(22f), sp(11f), MUTED, outline = false)
        text(c, roundStr, w * 0.17f, top + dp(50f), sp(26f), Color.WHITE, outline = false)
        text(c, "SCORE", w * 0.83f, top + dp(22f), sp(11f), MUTED, outline = false)
        text(c, scoreStr, w * 0.83f, top + dp(50f), sp(26f), GOLD, outline = false)
        text(c, "SIGN SAYS", w / 2f, top + dp(22f), sp(12f), Color.WHITE, outline = false)
        // lives
        val hy = top + dp(50f)
        for (i in 0 until SignGame.LIVES) {
            val x = w / 2f + (i - 1) * dp(30f)
            val on = i < g.lives
            val lostNow = g.phase == Phase.MISTAKE && i == g.lives && now - g.phaseStart < 600
            val scale = if (lostNow) 1f + 0.4f * sin((now - g.phaseStart) / 600f * PI.toFloat()) else 1f
            heart(c, x, hy, dp(10f) * scale, if (on || lostNow) LOSE_RED else Color.argb(70, 255, 255, 255))
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fillPaint.color = color
        c.drawCircle(x - r * 0.5f, y - r * 0.2f, r * 0.55f, fillPaint)
        c.drawCircle(x + r * 0.5f, y - r * 0.2f, r * 0.55f, fillPaint)
        heartPath.reset()
        heartPath.moveTo(x - r * 1.02f, y - r * 0.05f)
        heartPath.lineTo(x, y + r * 0.95f)
        heartPath.lineTo(x + r * 1.02f, y - r * 0.05f)
        heartPath.close()
        c.drawPath(heartPath, fillPaint)
    }

    private val heartPath = Path()
    private val trailPath = Path()
    private var trail = FloatArray(800)
    private var trailN = 0
    private var trailSeq = Int.MIN_VALUE
    private var trailEndAt = 0L
    private var trailOk = false

    /** One slot per sign in the sequence: ✓ done, ? still to come, the current one pulses. */
    private fun drawSlots(c: Canvas, g: SignGame, w: Float, now: Long) {
        val n = g.sequence.size
        if (n == 0 || g.phase == Phase.INTRO || g.phase == Phase.GAME_OVER) return
        val gap = dp(6f)
        val size = min(dp(34f), (w - dp(40f) - gap * (n - 1)) / n)
        val total = n * size + (n - 1) * gap
        var x = (w - total) / 2f
        val y = barBottom + dp(12f)
        for (i in 0 until n) {
            rect.set(x, y, x + size, y + size)
            val done = (g.phase == Phase.INPUT && i < g.step) || g.phase == Phase.ROUND_CLEAR
            val current = (g.phase == Phase.SHOW && i == g.showIndex) || (g.phase == Phase.INPUT && i == g.step)
            val wrong = g.phase == Phase.MISTAKE && i == g.step
            fillPaint.color = when {
                wrong -> LOSE_RED
                done -> WIN_GREEN
                current -> GOLD
                g.phase == Phase.SHOW && i < g.showIndex -> Color.argb(150, 255, 255, 255)
                else -> Color.argb(110, 0, 0, 0)
            }
            c.drawRoundRect(rect, size * 0.28f, size * 0.28f, fillPaint)
            if (current) {
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = Color.WHITE
                strokePaint.alpha = (150 + 100 * sin(now / 140f)).toInt()
                c.drawRoundRect(rect, size * 0.28f, size * 0.28f, strokePaint)
            }
            val label = when {
                done -> "✓"
                wrong -> "✗"
                g.phase == Phase.SHOW -> ""
                else -> "?"
            }
            if (label.isNotEmpty()) text(c, label, x + size / 2f, y + size / 2f, size * 0.5f, if (done || wrong) DARK else MUTED, outline = false)
            x += size + gap
        }
    }

    // ---------- phases ----------

    private fun drawIntro(c: Canvas, t: Long, w: Float, h: Float) {
        val cy = h * 0.42f
        val p = (t / 450f).coerceAtMost(1f)
        val fade = if (t > SignGame.INTRO_MS - 300) (SignGame.INTRO_MS - t) / 300f else 1f
        val a = (255 * fade.coerceIn(0f, 1f)).toInt()
        fillPaint.color = Color.BLACK
        fillPaint.alpha = a * 140 / 255
        c.drawRect(0f, cy - dp(90f), w, cy + dp(100f), fillPaint)
        text(c, "SIGN SAYS", w / 2f, cy - dp(48f), sp(42f), GOLD, a, 0.6f + 0.4f * easeOutBack(p))
        val p2 = ((t - 250) / 400f).coerceIn(0f, 1f)
        text(c, "Watch the signs…", w / 2f, cy + dp(6f), sp(20f), Color.WHITE, (a * p2).toInt())
        text(c, "then repeat them in order", w / 2f, cy + dp(34f), sp(20f), Color.WHITE, (a * p2).toInt())
        text(c, "Hold each sign until the ring fills", w / 2f, cy + dp(66f), sp(14f), SOFT_WHITE, (a * p2).toInt())
        text(c, "Shapes: draw them with one finger ☝", w / 2f, cy + dp(88f), sp(14f), SOFT_WHITE, (a * p2).toInt())
    }

    private fun drawShow(c: Canvas, g: SignGame, now: Long, w: Float, h: Float) {
        text(c, "WATCH", w / 2f, barBottom + dp(72f), sp(16f), SOFT_WHITE, (180 + 60 * sin(now / 200f)).toInt())
        val i = g.showIndex
        if (i < 0) return
        val sign = g.sequence[i]
        val t = now - g.showCardStart
        val pIn = (t / 260f).coerceIn(0f, 1f)
        val pOut = ((g.cardMs - t) / 160f).coerceIn(0f, 1f)
        val k = easeOutBack(pIn) * (0.85f + 0.15f * pOut)
        val a = (255 * min(pIn * 2f, pOut)).toInt().coerceIn(0, 255)
        val cy = h * 0.47f
        val cw = min(w * 0.72f, dp(300f)) * k
        val ch = cw * 1.25f
        drawSignCard(c, sign, w / 2f, cy, cw, a, GOLD, (t / g.cardMs.toFloat()) * 1.1f)
    }

    /** A sign on a card: picture, number badge, title and (for two hands / shapes) a hint. */
    private fun drawSignCard(c: Canvas, sign: Sign, cx: Float, cy: Float, cw: Float, a: Int, border: Int, trace: Float = -1f) {
        val ch = cw * 1.25f
        drawCard(c, cx, cy, cw, ch, a, border)
        painter.icon(c, sign, cx, cy - ch * 0.07f, cw * 0.74f, a, if (sign.kind == SignKind.SHAPE) trace else -1f)
        if (sign.number > 0) badge(c, sign.number, cx + cw * 0.36f, cy - ch * 0.38f, cw * 0.13f, a)
        val hasHint = sign.hint.isNotEmpty()
        val titleY = if (hasHint) cy + ch * 0.33f else cy + ch * 0.4f
        val title = if (sign.kind == SignKind.SHAPE) DRAW_TITLE[sign.ordinal] else sign.title
        text(c, title, cx, titleY, cw * 0.1f, DARK, a, outline = false)
        if (hasHint) text(c, sign.hint, cx, cy + ch * 0.43f, cw * 0.055f, Color.parseColor("#5D5D5D"), a, outline = false)
    }

    private fun drawInput(c: Canvas, g: SignGame, s: SignState?, now: Long, w: Float, h: Float) {
        val key = g.step * 100 + g.sequence.size
        if (key != stepFor) {
            stepFor = key
            stepStr = "Sign ${g.step + 1} of ${g.sequence.size}"
        }
        text(c, stepStr, w / 2f, barBottom + dp(72f), sp(18f), Color.WHITE)

        // time left for this sign
        val left = g.timeLeft(now)
        val sec = ((left + 999) / 1000).toInt()
        if (sec != secFor) {
            secFor = sec
            secStr = "${sec}s"
        }
        if (left < 4000) text(c, secStr, w / 2f, barBottom + dp(100f), sp(15f), if (left < 2000) LOSE_RED else GOLD)

        // ✓ flash for the sign just locked in
        val sinceLock = now - g.lockedAt
        val locked = g.lastLocked
        if (locked != null && sinceLock < 650) {
            val p = sinceLock / 650f
            val a = (255 * (1f - p)).toInt()
            val size = dp(140f) * (0.8f + 0.4f * easeOutBack(min(1f, p * 2f)))
            fillPaint.color = WIN_GREEN
            fillPaint.alpha = a * 200 / 255
            c.drawCircle(w / 2f, h * 0.42f, size / 2f, fillPaint)
            painter.icon(c, locked, w / 2f, h * 0.42f, size * 0.62f, a)
            text(c, "✓", w / 2f + size * 0.38f, h * 0.42f - size * 0.38f, sp(30f), Color.WHITE, a)
        }

        // bottom panel: what you're showing + the hold ring
        val cx = w / 2f
        val cy = h - dp(110f)
        val r = dp(52f)
        fillPaint.color = Color.argb(200, 14, 14, 24)
        c.drawCircle(cx, cy, r, fillPaint)
        strokePaint.strokeWidth = dp(7f)
        strokePaint.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(cx, cy, r, strokePaint)
        val holding = g.holding
        val shown = s?.sign
        if (holding != null) {
            strokePaint.color = GOLD
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, -90f, 360f * g.holdProgress, false, strokePaint)
        }
        // a drawing that wasn't recognised
        if (now - g.strokeFailAt < 1200) {
            text(c, "Didn't catch that shape, try again", w / 2f, h * 0.42f, sp(17f), LOSE_RED)
        }
        when {
            s == null || s.hands.isEmpty() -> text(c, "Show your hand ✋", cx, cy + r + dp(24f), sp(15f), SOFT_WHITE)
            s.drawing -> {
                text(c, "✍", cx, cy, sp(40f), YOU, outline = false)
                text(c, "Drawing… hold still to finish", cx, cy + r + dp(24f), sp(15f), YOU)
            }
            s.partial -> {
                text(c, "↕", cx, cy, sp(40f), GOLD, outline = false)
                text(c, "Move your whole hand into view", cx, cy + r + dp(24f), sp(15f), GOLD)
            }
            shown == null -> {
                text(c, "?", cx, cy, sp(40f), MUTED, outline = false)
                text(c, "Make a sign", cx, cy + r + dp(24f), sp(15f), SOFT_WHITE)
            }
            else -> {
                painter.icon(c, shown, cx, cy, r * 1.25f)
                text(c, shown.title, cx, cy + r + dp(24f), sp(16f), Color.WHITE)
            }
        }
    }

    private fun drawMistake(c: Canvas, g: SignGame, t: Long, w: Float, h: Float) {
        val shake = if (t < 350) sin(t / 25f) * dp(8f) * (1f - t / 350f) else 0f
        val wrong = g.wrongSign
        drawBig(c, if (wrong == null) "TOO SLOW!" else "OOPS!", LOSE_RED, t, w, h * 0.3f, sp(42f), shake)
        val a = alphaIn(t, 250)
        val exp = g.expected ?: return
        val cy = h * 0.52f
        val cw = min(w * 0.5f, dp(220f))
        text(c, "It was", w / 2f, cy - cw * 0.73f, sp(15f), SOFT_WHITE, a)
        drawSignCard(c, exp, w / 2f, cy, cw, a, WIN_GREEN, ((t % 2000) / 2000f))
        val line = if (wrong != null) "You showed: ${wrong.title}" else "Make each sign within 7 seconds"
        text(c, line, w / 2f, cy + cw * 0.95f, sp(15f), Color.WHITE, a)
        text(c, if (g.lives > 0) "Watch again…" else "", w / 2f, cy + cw * 0.95f + dp(28f), sp(14f), SOFT_WHITE, a)
    }

    private fun drawGameOver(c: Canvas, t: Long, w: Float, h: Float) {
        val p = (t / 400f).coerceAtMost(1f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (p * 175).toInt()
        c.drawRect(0f, 0f, w, h, fillPaint)
        val cy = h * 0.4f
        text(c, "GAME OVER", w / 2f, cy - dp(30f), sp(40f), LOSE_RED, 255, easeOutBack(p))
        val a = alphaIn(t - 250, 400)
        text(c, endLine1, w / 2f, cy + dp(20f), sp(18f), Color.WHITE, a)
        text(c, endLine2, w / 2f, cy + dp(52f), sp(16f), if (endLine2 == "NEW BEST!") GOLD else SOFT_WHITE, a)
    }

    // ---------- shared widgets ----------

    private fun drawCard(c: Canvas, cx: Float, cy: Float, cw: Float, ch: Float, alpha: Int, border: Int) {
        rect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
        fillPaint.color = Color.argb(alpha * 70 / 255, 0, 0, 0)
        rect.offset(0f, dp(6f))
        c.drawRoundRect(rect, dp(28f), dp(28f), fillPaint)
        rect.offset(0f, -dp(6f))
        fillPaint.color = CARD
        fillPaint.alpha = alpha
        c.drawRoundRect(rect, dp(28f), dp(28f), fillPaint)
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = border
        strokePaint.alpha = alpha
        c.drawRoundRect(rect, dp(28f), dp(28f), strokePaint)
    }

    private fun badge(c: Canvas, n: Int, x: Float, y: Float, r: Float, alpha: Int) {
        fillPaint.color = YOU
        fillPaint.alpha = alpha
        c.drawCircle(x, y, r, fillPaint)
        text(c, NUM[n], x, y, r * 1.3f, Color.WHITE, alpha, outline = false)
    }

    private fun drawBig(c: Canvas, s: String, color: Int, t: Long, w: Float, y: Float, size: Float, dx: Float = 0f) {
        val p = (t / 380f).coerceAtMost(1f)
        textPaint.textSize = size
        val fit = min(1f, (w - dp(32f)) / textPaint.measureText(s))
        text(c, s, w / 2f + dx, y, size * fit, color, 255, 0.5f + 0.5f * easeOutBack(p))
    }

    private fun alphaIn(t: Long, ms: Int): Int = (255 * (t / ms.toFloat()).coerceIn(0f, 1f)).toInt()

    /** Centered text with an optional dark outline so it reads over the camera. */
    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        alpha: Int = 255, scale: Float = 1f, outline: Boolean = true
    ) {
        if (alpha <= 0 || scale <= 0f || s.isEmpty()) return
        textPaint.textSize = size
        textPaint.color = color
        textPaint.alpha = alpha.coerceAtMost(255)
        val off = (textPaint.descent() + textPaint.ascent()) / 2f
        val scaled = scale != 1f
        if (scaled) {
            c.save()
            c.scale(scale, scale, x, y)
        }
        if (outline) {
            outlinePaint.textSize = size
            outlinePaint.strokeWidth = size * 0.12f
            outlinePaint.color = Color.BLACK
            outlinePaint.alpha = alpha.coerceAtMost(255) * 150 / 255
            c.drawText(s, x, y - off, outlinePaint)
        }
        c.drawText(s, x, y - off, textPaint)
        if (scaled) c.restore()
    }

    // ---------- confetti ----------

    private fun spawnConfetti() {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0 until confN) {
            cX[i] = Random.nextFloat() * w
            cY[i] = -Random.nextFloat() * h * 0.6f
            cVx[i] = (Random.nextFloat() - 0.5f) * dp(120f)
            cVy[i] = dp(170f) + Random.nextFloat() * dp(230f)
            cRot[i] = Random.nextFloat() * 6.28f
            cVr[i] = (Random.nextFloat() - 0.5f) * 12f
            cSize[i] = dp(5f) + Random.nextFloat() * dp(6f)
            cColor[i] = CONFETTI_COLORS[Random.nextInt(CONFETTI_COLORS.size)]
        }
        confettiActive = true
    }

    private fun drawConfetti(c: Canvas, dt: Float, h: Float) {
        var alive = false
        for (i in 0 until confN) {
            cRot[i] += cVr[i] * dt
            cX[i] += (cVx[i] + sin(cRot[i]) * dp(30f)) * dt
            cY[i] += cVy[i] * dt
            if (cY[i] > h + dp(20f)) continue
            alive = true
            val hw = cSize[i] * (0.2f + 0.8f * abs(cos(cRot[i]))) / 2f
            val hh = cSize[i] * 0.35f
            fillPaint.color = cColor[i]
            c.drawRect(cX[i] - hw, cY[i] - hh, cX[i] + hw, cY[i] + hh, fillPaint)
        }
        confettiActive = alive
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private companion object {
        val NUM = Array(11) { it.toString() }
        val TIP_IDS = intArrayOf(4, 8, 12, 16, 20)
        val DRAW_TITLE = Array(Sign.entries.size) { "Draw a ${Sign.entries[it].title.lowercase()}" }
        val GOLD = Color.parseColor("#FFD54F")
        val WIN_GREEN = Color.parseColor("#69F0AE")
        val LOSE_RED = Color.parseColor("#FF5252")
        val MUTED = Color.parseColor("#B0B3C6")
        val SOFT_WHITE = Color.parseColor("#E6FFFFFF")
        val YOU = Color.parseColor("#4FC3F7")
        val CARD = Color.parseColor("#FFF8E7")
        val DARK = Color.parseColor("#1A1A1A")
        val CONFETTI_COLORS = intArrayOf(
            Color.parseColor("#FFD54F"), Color.parseColor("#FFF59D"), Color.parseColor("#4FC3F7"),
            Color.parseColor("#FF8A80"), Color.parseColor("#69F0AE"), Color.WHITE
        )
    }
}

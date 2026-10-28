package com.demo.swarhands.games.rps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import com.demo.swarhands.audio.Sfx
import com.demo.swarhands.audio.SfxPlayer
import com.demo.swarhands.gesture.HandSkeleton
import com.demo.swarhands.gesture.ViewTransform
import com.demo.swarhands.games.rps.RpsGame.Outcome
import com.demo.swarhands.games.rps.RpsGame.Phase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the whole Rock Paper Scissors game over the camera preview.
 *
 * Low-end friendly:
 *  - glove images are pre-rendered bitmaps, confetti uses preallocated arrays
 *  - no object allocation inside onDraw (strings are cached)
 *  - runs at display rate only during animations; otherwise redraws at the camera's ~30 fps
 */
class RpsGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), RpsGame.Listener {

    var sfx: SfxPlayer? = null
    var onPhaseChanged: ((Phase) -> Unit)? = null
    val game = RpsGame(this)

    @Volatile private var liveMove: RpsMove? = null
    @Volatile private var handPoints: List<PointF>? = null

    // ---------- units ----------
    private val dpF = resources.displayMetrics.density
    private val spF = dpF * resources.configuration.fontScale.coerceAtMost(1.3f)
    private fun dp(v: Float) = v * dpF
    private fun sp(v: Float) = v * spF

    // ---------- paints (created once) ----------
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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val topScrim = Paint()
    private val bottomScrim = Paint()
    private val vignette = Paint()
    private val rect = RectF()

    // ---------- layout ----------
    private var gloves = arrayOfNulls<Bitmap>(3)
    private var gloveCy = 0f
    private var gloveBottom = 0f
    private var centerY = 0f
    private val barTop get() = dp(14f)
    private val barBottom get() = dp(14f) + dp(72f)

    // ---------- confetti (preallocated pool) ----------
    private val confN = 90
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
    private var roundLabel = "ROUND 1"
    private var roundLabelFor = 1
    private var finalScore = ""
    private var lastFrameAt = 0L

    // =====================================================================
    // Public API (thread-safe parts are marked)
    // =====================================================================

    /** Thread-safe: called from the tracking thread. */
    fun transformFor(imgW: Int, imgH: Int): ViewTransform? {
        val w = width
        val h = height
        if (w == 0 || h == 0) return null
        return ViewTransform(w, h, imgW, imgH)
    }

    /** Thread-safe: called from the tracking thread with the smoothed gesture. */
    fun submitHand(move: RpsMove?, points: List<PointF>?) {
        liveMove = move
        handPoints = points
        postInvalidate()
    }

    /** UI thread. */
    fun restartGame() {
        game.startGame(SystemClock.uptimeMillis())
        invalidate()
    }

    // =====================================================================
    // RpsGame.Listener (called on the UI thread from game.update)
    // =====================================================================

    override fun onSfx(sfx: Sfx) {
        this.sfx?.play(sfx)
    }

    override fun onBeat() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onPhaseChanged(phase: Phase) {
        if (phase == Phase.GAME_OVER) {
            finalScore = "You ${game.playerScore}  –  ${game.aiScore} AI"
            if (game.playerWonGame) spawnConfetti()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else if (phase == Phase.REVEAL && game.outcome != Outcome.DRAW) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        post { onPhaseChanged?.invoke(phase) } // never touch other views mid-draw
    }

    // =====================================================================
    // Layout
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val gloveW = min(w * 0.42f, dp(190f)).toInt().coerceIn(64, 520)
        gloves = Array(3) { GloveRenderer.render(RpsMove.entries[it], gloveW) }
        val gloveH = gloves[0]!!.height
        gloveCy = barBottom + dp(12f) + gloveH / 2f
        gloveBottom = gloveCy + gloveH / 2f
        centerY = max(gloveBottom + dp(80f), h * 0.5f)

        topScrim.shader = LinearGradient(
            0f, 0f, 0f, h * 0.28f, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        bottomScrim.shader = LinearGradient(
            0f, h * 0.70f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP
        )
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(210, 170, 0, 20)), floatArrayOf(0.4f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (gloves[0] == null) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else (now - lastFrameAt).coerceIn(1, 50) / 1000f
        lastFrameAt = now

        game.update(now, liveMove)
        val phase = game.phase
        val t = now - game.phaseStart
        val w = width.toFloat()
        val h = height.toFloat()

        // screen shake when you lose the match
        val shake = phase == Phase.GAME_OVER && !game.playerWonGame && t < 450
        if (shake) {
            c.save()
            c.translate(sin(t / 16f) * dp(10f) * (1f - t / 450f), 0f)
        }

        c.drawRect(0f, 0f, w, h * 0.28f, topScrim)
        c.drawRect(0f, h * 0.70f, w, h, bottomScrim)
        drawHand(c)

        if (phase == Phase.GAME_OVER) drawGameOverBackdrop(c, t, w, h)
        drawGlove(c, now, t, phase, w)
        drawScoreBar(c, now, w)

        when (phase) {
            Phase.WAIT_START -> drawWaitStart(c, now, w)
            Phase.INTRO -> drawIntro(c, t, w)
            Phase.CHANT -> drawChant(c, t, w)
            Phase.NO_HAND -> drawNoHand(c, t, w)
            Phase.REVEAL -> drawReveal(c, t, w)
            Phase.GAME_OVER -> drawGameOver(c, now, t, w)
        }
        if (phase != Phase.GAME_OVER) drawLiveChip(c, w, h)
        if (confettiActive) drawConfetti(c, dt, h)
        if (shake) c.restore()

        if (needsAnimation(phase, t)) postInvalidateOnAnimation()
    }

    private fun needsAnimation(phase: Phase, t: Long): Boolean = when (phase) {
        Phase.INTRO, Phase.CHANT, Phase.NO_HAND, Phase.REVEAL -> true
        Phase.GAME_OVER -> t < 2500 || confettiActive
        Phase.WAIT_START -> false // camera frames (~30 fps) are enough here
    }

    // ---------- score bar ----------

    private fun drawScoreBar(c: Canvas, now: Long, w: Float) {
        val top = barTop
        val bottom = barBottom
        rect.set(dp(16f), top, w - dp(16f), bottom)
        fillPaint.color = Color.argb(205, 14, 14, 24)
        c.drawRoundRect(rect, dp(22f), dp(22f), fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(rect, dp(22f), dp(22f), strokePaint)

        // hold the old score for a moment, then bump it in
        val pending = now < game.scoreChangedAt
        val bumpT = (now - game.scoreChangedAt) / 450f
        val bump = !pending && bumpT in 0f..1f
        val you = if (pending && game.lastScorer == Outcome.WIN) game.playerScore - 1 else game.playerScore
        val ai = if (pending && game.lastScorer == Outcome.LOSE) game.aiScore - 1 else game.aiScore
        val youScale = if (bump && game.lastScorer == Outcome.WIN) 1f + 0.7f * sin(PI.toFloat() * bumpT) else 1f
        val aiScale = if (bump && game.lastScorer == Outcome.LOSE) 1f + 0.7f * sin(PI.toFloat() * bumpT) else 1f

        text(c, "YOU", w * 0.2f, top + dp(20f), sp(12f), YOU_COLOR, outline = false)
        text(c, DIGITS[you.coerceIn(0, 9)], w * 0.2f, top + dp(49f), sp(32f), Color.WHITE, scale = youScale, outline = false)
        text(c, "AI", w * 0.8f, top + dp(20f), sp(12f), AI_COLOR, outline = false)
        text(c, DIGITS[ai.coerceIn(0, 9)], w * 0.8f, top + dp(49f), sp(32f), Color.WHITE, scale = aiScale, outline = false)

        text(c, "BEST OF 3", w / 2f, top + dp(22f), sp(11f), MUTED, outline = false)
        val center = if (game.phase == Phase.WAIT_START) "VS" else roundLabel()
        text(c, center, w / 2f, top + dp(48f), sp(17f), Color.WHITE, outline = false)

        // win pips: two dots per side
        for (i in 0 until RpsGame.WIN_SCORE) {
            drawPip(c, w * 0.2f + dp(30f) + i * dp(12f), top + dp(49f), i < you, YOU_COLOR)
            drawPip(c, w * 0.8f - dp(30f) - i * dp(12f), top + dp(49f), i < ai, AI_COLOR)
        }
    }

    private fun drawPip(c: Canvas, x: Float, y: Float, on: Boolean, color: Int) {
        fillPaint.color = if (on) color else Color.argb(50, 255, 255, 255)
        c.drawCircle(x, y, dp(4f), fillPaint)
    }

    private fun roundLabel(): String {
        if (roundLabelFor != game.round) {
            roundLabelFor = game.round
            roundLabel = "ROUND ${game.round}"
        }
        return roundLabel
    }

    // ---------- AI glove ----------

    private fun drawGlove(c: Canvas, now: Long, t: Long, phase: Phase, w: Float) {
        val move = when (phase) {
            Phase.REVEAL, Phase.GAME_OVER -> game.aiMove ?: RpsMove.ROCK
            else -> RpsMove.ROCK
        }
        val bmp = gloves[move.ordinal] ?: return
        var dy = 0f
        var rot = 0f
        var scale = 1f
        var alpha = 255

        when (phase) {
            Phase.WAIT_START, Phase.INTRO, Phase.NO_HAND -> dy = sin(now / 380f) * dp(5f)
            Phase.CHANT -> if (t < RpsGame.CHANT_BEAT_MS * 3) {
                // pump down on every word, like a real player
                val p = (t % RpsGame.CHANT_BEAT_MS) / RpsGame.CHANT_BEAT_MS.toFloat()
                dy = sin(p * PI.toFloat()) * dp(28f)
            }
            Phase.REVEAL -> {
                val p = (t / 280f).coerceAtMost(1f)
                scale = 0.55f + 0.45f * easeOutBack(p)
                rot = sin(t / 55f) * 8f * (1f - p)
            }
            Phase.GAME_OVER -> if (game.playerWonGame) {
                // defeated glove drifts away
                val p = (t / 900f).coerceIn(0f, 1f)
                dy = -p * p * p * dp(240f)
                rot = p * 25f
                alpha = (255 * (1f - p)).toInt()
            } else {
                // victorious glove "laughs"
                rot = sin(now / 70f) * 7f
                dy = sin(now / 140f) * dp(4f)
            }
        }
        if (alpha <= 0) return

        c.save()
        c.translate(w / 2f, gloveCy + dy)
        if (rot != 0f) c.rotate(rot)
        if (scale != 1f) c.scale(scale, scale)
        bitmapPaint.alpha = alpha
        c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, bitmapPaint)
        c.restore()
    }

    // ---------- phases ----------

    private fun drawWaitStart(c: Canvas, now: Long, w: Float) {
        text(c, "STONE · PAPER · SCISSORS", w / 2f, centerY - dp(34f), sp(21f), Color.WHITE)
        text(c, "Make a fist ✊ and hold it for 3 seconds", w / 2f, centerY + dp(2f), sp(14f), SOFT_WHITE)
        drawHoldRing(c, w / 2f, centerY + dp(92f), dp(48f), game.holdProgress, now)
    }

    private fun drawIntro(c: Canvas, t: Long, w: Float) {
        val beat = (t / RpsGame.INTRO_BEAT_MS).toInt().coerceIn(0, 2)
        val p = (t % RpsGame.INTRO_BEAT_MS) / RpsGame.INTRO_BEAT_MS.toFloat()

        // expanding ring burst behind the number
        strokePaint.strokeWidth = dp(6f) * (1f - p)
        strokePaint.color = GOLD
        strokePaint.alpha = ((1f - p) * 170).toInt()
        c.drawCircle(w / 2f, centerY, dp(46f) + dp(90f) * easeOutCubic(p), strokePaint)

        val scale = 1.9f - 0.9f * easeOutBack((p * 2.4f).coerceAtMost(1f))
        val alpha = if (p < 0.7f) 255 else ((1f - p) / 0.3f * 255).toInt()
        text(c, DIGITS[beat + 1], w / 2f, centerY, sp(120f), GOLD, alpha, scale)
        text(c, "Get ready!", w / 2f, centerY + dp(92f), sp(16f), SOFT_WHITE)
    }

    private fun drawChant(c: Canvas, t: Long, w: Float) {
        text(c, roundLabel(), w / 2f, centerY - dp(64f), sp(14f), MUTED)
        val beat = (t / RpsGame.CHANT_BEAT_MS).toInt()
        if (beat <= 2) {
            val p = (t % RpsGame.CHANT_BEAT_MS) / RpsGame.CHANT_BEAT_MS.toFloat()
            val scale = 1.5f - 0.5f * easeOutBack((p * 3f).coerceAtMost(1f))
            val last = beat == 2
            text(c, CHANT_WORDS[beat], w / 2f, centerY, if (last) sp(54f) else sp(46f), if (last) GOLD else Color.WHITE, 255, scale)
        } else {
            text(c, CHANT_WORDS[2], w / 2f, centerY, sp(54f), GOLD)
        }
        if (t >= RpsGame.CAPTURE_START_MS) {
            text(c, "Show your move!", w / 2f, centerY + dp(56f), sp(15f), SOFT_WHITE)
        }
    }

    private fun drawNoHand(c: Canvas, t: Long, w: Float) {
        val p = (t / 300f).coerceAtMost(1f)
        text(c, "Couldn't see your hand 👀", w / 2f, centerY, sp(22f), Color.WHITE, 255, 0.7f + 0.3f * easeOutBack(p))
        text(c, "Throw your move on \"SCISSORS!\"", w / 2f, centerY + dp(36f), sp(14f), SOFT_WHITE)
    }

    private fun drawReveal(c: Canvas, t: Long, w: Float) {
        val ai = game.aiMove ?: return
        val you = game.playerMove ?: return
        val o = game.outcome ?: return

        val labelAlpha = (t / 250f * 255).toInt().coerceAtMost(255)
        drawPill(c, ai.aiText, w / 2f, gloveBottom + dp(16f), sp(14f), Color.WHITE, Color.argb(170, 0, 0, 0), AI_COLOR, labelAlpha)

        val p = ((t - 150) / 380f).coerceIn(0f, 1f)
        val msg = when (o) {
            Outcome.WIN -> "YOU WIN THE ROUND!"
            Outcome.LOSE -> "AI TAKES THIS ROUND"
            Outcome.DRAW -> "DRAW! GO AGAIN"
        }
        val color = when (o) {
            Outcome.WIN -> WIN_GREEN
            Outcome.LOSE -> LOSE_RED
            Outcome.DRAW -> GOLD
        }
        if (p > 0f) {
            c.save()
            val s = easeOutBack(p)
            c.scale(s, s, w / 2f, centerY)
            drawPill(c, msg, w / 2f, centerY, sp(20f), color, Color.argb(210, 10, 10, 18), color, 255)
            c.restore()
        }

        val youP = ((t - 300) / 350f).coerceIn(0f, 1f)
        if (youP > 0f) {
            text(c, you.emoji, w / 2f, centerY + dp(80f), sp(56f), Color.WHITE, (youP * 255).toInt(), 0.6f + 0.4f * easeOutBack(youP), outline = false)
            text(c, you.youText, w / 2f, centerY + dp(124f), sp(15f), YOU_COLOR, (youP * 255).toInt())
        }
    }

    private fun drawGameOverBackdrop(c: Canvas, t: Long, w: Float, h: Float) {
        val p = (t / 400f).coerceAtMost(1f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (p * 165).toInt()
        c.drawRect(0f, 0f, w, h, fillPaint)
        if (!game.playerWonGame) {
            vignette.alpha = (p * 255).toInt()
            c.drawRect(0f, 0f, w, h, vignette)
        }
    }

    private fun drawGameOver(c: Canvas, now: Long, t: Long, w: Float) {
        val won = game.playerWonGame
        val iconP = (t / 500f).coerceAtMost(1f)
        val float = if (t > 500) -abs(sin(now / 300f)) * dp(6f) else 0f
        text(c, if (won) "🏆" else "😵", w / 2f, centerY - dp(78f) + float, sp(68f), Color.WHITE, 255, easeOutBack(iconP), outline = false)

        val titleP = ((t - 150) / 450f).coerceIn(0f, 1f)
        if (titleP > 0f) {
            text(c, if (won) "YOU WIN!" else "AI WINS", w / 2f, centerY, sp(56f), if (won) GOLD else LOSE_RED, 255, easeOutBack(titleP))
        }
        val subP = ((t - 450) / 400f).coerceIn(0f, 1f)
        if (subP > 0f) {
            text(c, finalScore, w / 2f, centerY + dp(46f), sp(18f), Color.WHITE, (subP * 255).toInt())
            text(c, if (won) "The glove never stood a chance" else "Better luck next time!", w / 2f, centerY + dp(74f), sp(14f), SOFT_WHITE, (subP * 255).toInt())
        }
        if (t >= RpsGame.RESTART_DELAY_MS) {
            val a = ((t - RpsGame.RESTART_DELAY_MS) / 400f * 255).toInt().coerceAtMost(255)
            text(c, "Hold a fist ✊ to play again", w / 2f, centerY + dp(118f), sp(14f), SOFT_WHITE, a)
            drawHoldRing(c, w / 2f, centerY + dp(172f), dp(34f), game.holdProgress, now, a)
        }
    }

    // ---------- shared widgets ----------

    private fun drawHoldRing(c: Canvas, cx: Float, cy: Float, r: Float, progress: Float, now: Long, alpha: Int = 255) {
        strokePaint.strokeWidth = dp(6f)
        strokePaint.color = Color.WHITE
        strokePaint.alpha = alpha * 70 / 255
        c.drawCircle(cx, cy, r, strokePaint)
        if (progress > 0f) {
            strokePaint.color = GOLD
            strokePaint.alpha = alpha
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, -90f, 360f * progress, false, strokePaint)
        }
        val pulse = if (progress > 0f) 1f + 0.08f * sin(now / 90f) else 1f + 0.04f * sin(now / 400f)
        text(c, "✊", cx, cy, r * 0.85f, Color.WHITE, alpha, pulse, outline = false)
        if (progress > 0f) {
            val left = ceil(3f * (1f - progress)).toInt().coerceIn(1, 3)
            text(c, DIGITS[left], cx, cy + r + dp(22f), sp(18f), GOLD, alpha)
        }
    }

    private fun drawLiveChip(c: Canvas, w: Float, h: Float) {
        val move = liveMove
        val label = move?.youText ?: "Show your hand to the camera"
        drawPill(c, label, w / 2f, h - dp(56f), sp(15f), if (move != null) Color.WHITE else SOFT_WHITE,
            Color.argb(180, 0, 0, 0), if (move != null) YOU_COLOR else Color.argb(80, 255, 255, 255), 255)
    }

    private fun drawHand(c: Canvas) {
        val pts = handPoints ?: return
        if (pts.size < 21) return
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = Color.argb(130, 255, 255, 255)
        for ((a, b) in HandSkeleton.BONES) c.drawLine(pts[a].x, pts[a].y, pts[b].x, pts[b].y, strokePaint)
        fillPaint.color = Color.argb(200, 79, 195, 247)
        for (p in pts) c.drawCircle(p.x, p.y, dp(3.5f), fillPaint)
    }

    private fun drawPill(
        c: Canvas, s: String, cx: Float, cy: Float, size: Float,
        textColor: Int, bg: Int, border: Int, alpha: Int
    ) {
        if (alpha <= 0) return
        textPaint.textSize = size
        val half = textPaint.measureText(s) / 2f + dp(16f)
        val hh = size * 0.62f + dp(6f)
        rect.set(cx - half, cy - hh, cx + half, cy + hh)
        fillPaint.color = bg
        fillPaint.alpha = Color.alpha(bg) * alpha / 255
        c.drawRoundRect(rect, hh, hh, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = border
        strokePaint.alpha = Color.alpha(border) * alpha / 255
        c.drawRoundRect(rect, hh, hh, strokePaint)
        text(c, s, cx, cy, size, textColor, alpha, outline = false)
    }

    /** Centered text with an optional dark outline so it reads over any camera background. */
    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        alpha: Int = 255, scale: Float = 1f, outline: Boolean = true
    ) {
        if (alpha <= 0 || scale <= 0f) return
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
            // fake 3D flip by squashing width with cos(rotation) — no canvas save/rotate needed
            val hw = cSize[i] * (0.2f + 0.8f * abs(cos(cRot[i]))) / 2f
            val hh = cSize[i] * 0.35f
            fillPaint.color = cColor[i]
            c.drawRect(cX[i] - hw, cY[i] - hh, cX[i] + hw, cY[i] + hh, fillPaint)
        }
        confettiActive = alive
    }

    // ---------- easing ----------

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

    private companion object {
        val DIGITS = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
        val CHANT_WORDS = arrayOf("STONE", "PAPER", "SCISSORS!")
        val GOLD = Color.parseColor("#FFD54F")
        val YOU_COLOR = Color.parseColor("#4FC3F7")
        val AI_COLOR = Color.parseColor("#FF8A80")
        val WIN_GREEN = Color.parseColor("#69F0AE")
        val LOSE_RED = Color.parseColor("#FF5252")
        val MUTED = Color.parseColor("#B0B3C6")
        val SOFT_WHITE = Color.parseColor("#E6FFFFFF")
        val CONFETTI_COLORS = intArrayOf(
            Color.parseColor("#FFD54F"), Color.parseColor("#4FC3F7"), Color.parseColor("#FF8A80"),
            Color.parseColor("#69F0AE"), Color.parseColor("#B388FF"), Color.WHITE
        )
    }
}

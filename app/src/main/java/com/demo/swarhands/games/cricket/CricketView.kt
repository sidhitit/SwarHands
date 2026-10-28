package com.demo.swarhands.games.cricket

import android.content.Context
import android.graphics.Bitmap
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
import com.demo.swarhands.games.cricket.CricketGame.BallType
import com.demo.swarhands.games.cricket.CricketGame.Companion.CREASE_Z
import com.demo.swarhands.games.cricket.CricketGame.Companion.FAR_Z
import com.demo.swarhands.games.cricket.CricketGame.Companion.HOUSES
import com.demo.swarhands.games.cricket.CricketGame.Companion.STUMPS_Z
import com.demo.swarhands.games.cricket.CricketGame.Companion.STUMP_H
import com.demo.swarhands.games.cricket.CricketGame.Companion.STUMP_HALF
import com.demo.swarhands.games.cricket.CricketGame.Companion.WALL_X
import com.demo.swarhands.games.cricket.CricketGame.Companion.WINDOWS
import com.demo.swarhands.games.cricket.CricketGame.Outcome
import com.demo.swarhands.games.cricket.CricketGame.Phase
import com.demo.swarhands.gesture.HandSkeleton
import com.demo.swarhands.gesture.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the gully over the camera preview, seen from behind the batsman's stumps.
 *
 * Lightweight by design:
 *  - the static street (sky, houses, road, pitch) is rendered once into a bitmap
 *  - the bowler, ball, stumps and bat are plain shapes drawn each frame
 *  - paints, paths, particles and strings are allocated once, never inside onDraw
 */
class CricketView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), CricketGame.Listener {

    var sfx: SfxPlayer? = null
    var onPhaseChanged: ((Phase) -> Unit)? = null
    var game: CricketGame? = null
        private set

    @Volatile private var bat: BatState? = null

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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 248 }
    private val rect = RectF()
    private val path = Path()

    // ---------- layout ----------
    private var sceneBmp: Bitmap? = null
    private val barTop get() = dp(14f)
    private val barBottom get() = dp(90f)

    // ---------- bat trail ----------
    private val trailN = 7
    private val trailX = FloatArray(trailN)
    private val trailY = FloatArray(trailN)
    private var trailPos = 0
    private var trailCount = 0

    // ---------- sparks / shards (preallocated pool) ----------
    private val sparkN = 48
    private val sX = FloatArray(sparkN)
    private val sY = FloatArray(sparkN)
    private val sVx = FloatArray(sparkN)
    private val sVy = FloatArray(sparkN)
    private val sLife = FloatArray(sparkN)
    private val sColor = IntArray(sparkN)
    private val sShard = BooleanArray(sparkN)
    private var sparksActive = false

    // ---------- confetti ----------
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
    private var scoreStr = "0/0"
    private var scoreFor = -1
    private var sideStr = ""
    private var sideFor = -1
    private var centerStr = ""
    private var centerFor = -1
    private var titleStr = ""
    private var introTitle = ""
    private var introLine1 = ""
    private var introLine2 = ""
    private var bigStr = ""
    private var subStr = ""
    private var timingStr = ""
    private var bigColor = Color.WHITE
    private var deliveryStr = ""
    private var endTitle = ""
    private var endLine1 = ""
    private var endLine2 = ""
    /** Game clock when the result text should appear (after the ball lands for some shots). */
    private var textAt = Double.MAX_VALUE
    private var lastFrameAt = 0L

    // =====================================================================
    // Public API
    // =====================================================================

    fun start(mode: CricketMode, level: Int, best: Int) {
        game = CricketGame(mode, level, best, this).also {
            if (width > 0) it.layout(width.toFloat(), height.toFloat())
        }
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
    fun submitBat(state: BatState) {
        bat = state
        postInvalidate()
    }

    fun restartGame() {
        game?.restart()
        confettiActive = false
        invalidate()
    }

    // =====================================================================
    // CricketGame.Listener (UI thread, from game.update)
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
            Phase.INTRO -> {
                textAt = Double.MAX_VALUE
                if (g.mode == CricketMode.CHASE) {
                    val lv = g.levelInfo
                    titleStr = "LEVEL ${g.level}"
                    introTitle = "LEVEL ${g.level}"
                    introLine1 = "Chase ${lv.target} off ${lv.balls} balls"
                    introLine2 = "${lv.wickets} wickets in hand"
                } else {
                    titleStr = "SUPER OVER"
                    introTitle = "SUPER OVER"
                    introLine1 = "Bat until you lose ${CricketGame.SUPER_WICKETS} wickets"
                    introLine2 = "It gets quicker every over"
                }
            }
            Phase.RUNUP -> textAt = Double.MAX_VALUE
            Phase.DELIVERY -> deliveryStr = "${g.type.label} · ${g.kmh} km/h"
            Phase.LEVEL_CLEAR -> {
                endTitle = "LEVEL ${g.level} WON!"
                endLine1 = "${g.runs} runs off ${g.balls} balls"
                val next = CricketGame.LEVELS[g.level]
                endLine2 = "Next: chase ${next.target} off ${next.balls}"
                spawnConfetti()
            }
            Phase.GAME_OVER -> {
                if (g.mode == CricketMode.CHASE) {
                    if (g.won) {
                        endTitle = "CHAMPION! 🏆"
                        endLine1 = "All ${CricketGame.LEVELS.size} levels cleared"
                        endLine2 = "${g.runs} runs in the final chase"
                        spawnConfetti()
                    } else {
                        endTitle = "LEVEL ${g.level} LOST"
                        endLine1 = "Needed ${g.target - g.runs} more" +
                            if (g.wickets >= g.maxWickets) " · all out" else " · out of balls"
                        endLine2 = if (g.best > 0) "Best: level ${g.best} cleared" else "Try again!"
                    }
                } else {
                    endTitle = "ALL OUT"
                    endLine1 = "${g.runs} runs off ${g.balls} balls"
                    endLine2 = if (g.newBest) "NEW BEST!" else "Best: ${g.best}"
                    if (g.newBest) spawnConfetti()
                }
            }
            else -> Unit
        }
        post { onPhaseChanged?.invoke(phase) } // never touch other views mid-draw
    }

    override fun onResolved() {
        val g = game ?: return
        val runs = g.shotRuns
        subStr = ""
        when (g.outcome) {
            Outcome.SIX -> { bigStr = "SIX!"; bigColor = GOLD; subStr = "Out of the gully!" }
            Outcome.FOUR -> { bigStr = "FOUR!"; bigColor = WIN_GREEN; subStr = "Raced to the wall" }
            Outcome.RUNS -> { bigStr = if (runs == 1) "1 RUN" else "$runs RUNS"; bigColor = Color.WHITE }
            Outcome.DOT -> { bigStr = "DOT BALL"; bigColor = MUTED; subStr = "Solid defence" }
            Outcome.BEATEN -> { bigStr = "BEATEN!"; bigColor = MUTED; subStr = "Swing and a miss" }
            Outcome.LEFT -> {
                bigStr = if (g.type == BallType.BOUNCER) "WELL LEFT" else "LEFT ALONE"
                bigColor = MUTED
                subStr = if (g.type == BallType.BOUNCER) "Ducked under the bouncer" else "Swing when it reaches you"
            }
            Outcome.BOWLED -> { bigStr = "BOWLED!"; bigColor = LOSE_RED; subStr = "Stumps everywhere" }
            Outcome.CAUGHT -> { bigStr = "CAUGHT!"; bigColor = LOSE_RED; subStr = "Hit it harder to clear him" }
            Outcome.CAUGHT_BEHIND -> { bigStr = "CAUGHT BEHIND!"; bigColor = LOSE_RED; subStr = "Just an edge" }
            Outcome.WINDOW_OUT -> { bigStr = "OUT! WINDOW BROKEN"; bigColor = LOSE_RED; subStr = "Gully rule: break it, you're out" }
            Outcome.GOLDEN -> { bigStr = "GOLDEN WINDOW!"; bigColor = GOLD; subStr = "+10 bonus runs" }
            Outcome.WALL -> { bigStr = "2 RUNS"; bigColor = Color.WHITE; subStr = "Off the wall, phew!" }
        }
        timingStr = if (!g.contact) "" else {
            val dt = g.timingMs
            when {
                abs(dt) <= 45f -> TIMING_PERFECT
                dt < 0f -> "Early"
                else -> "Late"
            }
        }
        textAt = if (g.revealOnImpact) Double.MAX_VALUE else g.clock
        trailCount = 0
    }

    override fun onImpact() {
        val g = game ?: return
        if (textAt == Double.MAX_VALUE) textAt = g.clock
        when (g.outcome) {
            Outcome.SIX -> spawnConfetti()
            Outcome.WINDOW_OUT, Outcome.GOLDEN -> {
                val win = WINDOWS[max(g.targetWindow, 0)]
                spawnSparks(g.sx(win.side * WALL_X, win.zc), g.sy(win.yc, win.zc), 40, g.outcome == Outcome.GOLDEN)
            }
            else -> Unit
        }
    }

    // =====================================================================
    // Layout
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val layout = game ?: CricketGame(CricketMode.CHASE, 1, 0, this)
        layout.layout(w.toFloat(), h.toFloat())
        sceneBmp?.recycle()
        sceneBmp = renderScene(layout, w, h)
    }

    /** The street, drawn once: sky, far house, road and pitch, both rows of houses, cables. */
    private fun renderScene(g: CricketGame, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pth = Path()

        // evening sky
        p.shader = LinearGradient(
            0f, 0f, 0f, g.horizonY + h * 0.1f,
            intArrayOf(Color.parseColor("#1E2A5A"), Color.parseColor("#6A4C93"), Color.parseColor("#F4A261")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // road between the walls
        p.shader = LinearGradient(
            0f, g.sy(0f, FAR_Z), 0f, h.toFloat(),
            Color.parseColor("#5B5F66"), Color.parseColor("#3A3D42"), Shader.TileMode.CLAMP
        )
        groundQuad(pth, g, -WALL_X, WALL_X, 0.35f, FAR_Z)
        c.drawPath(pth, p)
        p.shader = null

        // the pitch: a chalk-marked strip of dusty road
        p.color = Color.parseColor("#C9A66B")
        groundQuad(pth, g, -0.62f, 0.62f, 0.5f, 5.6f)
        c.drawPath(pth, p)
        p.color = Color.argb(70, 0, 0, 0)
        groundQuad(pth, g, -0.62f, 0.62f, 2.0f, 3.0f) // worn patch where it pitches
        c.drawPath(pth, p)
        p.color = Color.argb(220, 255, 255, 255)
        for (z in floatArrayOf(CREASE_Z, CricketGame.RELEASE_Z)) {
            groundQuad(pth, g, -0.62f, 0.62f, z - 0.02f, z + 0.02f)
            c.drawPath(pth, p)
        }

        // far house closing the street
        p.color = Color.parseColor("#B5838D")
        pth.reset()
        pth.moveTo(g.sx(-WALL_X, FAR_Z), g.sy(0f, FAR_Z))
        pth.lineTo(g.sx(-WALL_X, FAR_Z), g.sy(3.4f, FAR_Z))
        pth.lineTo(g.sx(WALL_X, FAR_Z), g.sy(3.4f, FAR_Z))
        pth.lineTo(g.sx(WALL_X, FAR_Z), g.sy(0f, FAR_Z))
        pth.close()
        c.drawPath(pth, p)
        p.color = Color.parseColor("#6D4C41")
        val s12 = g.scale(FAR_Z)
        c.drawRect(g.sx(-0.3f, FAR_Z), g.sy(1.6f, FAR_Z), g.sx(0.3f, FAR_Z), g.sy(0f, FAR_Z), p)
        p.color = Color.parseColor("#FFE082")
        c.drawRect(g.sx(-1.6f, FAR_Z), g.sy(2.8f, FAR_Z), g.sx(-0.9f, FAR_Z), g.sy(2.1f, FAR_Z), p)
        c.drawRect(g.sx(0.9f, FAR_Z), g.sy(2.8f, FAR_Z), g.sx(1.6f, FAR_Z), g.sy(2.1f, FAR_Z), p)

        // far stumps at the bowler's end
        p.color = Color.parseColor("#F5E6C8")
        for (k in -1..1) {
            val x = g.sx(k * 0.11f, 5.25f)
            c.drawRect(x - s12 * 0.18f, g.sy(STUMP_H, 5.25f), x + s12 * 0.18f, g.sy(0f, 5.25f), p)
        }

        // low compound walls near the camera
        for (side in intArrayOf(-1, 1)) {
            p.color = Color.parseColor("#8D8D8D")
            wallQuad(pth, g, side, 0.35f, 1.2f, 0f, 1.2f)
            c.drawPath(pth, p)
            p.color = Color.parseColor("#A9A9A9")
            wallQuad(pth, g, side, 0.35f, 1.2f, 1.05f, 1.2f)
            c.drawPath(pth, p)
        }

        // houses, far to near
        val order = HOUSES.sortedByDescending { it.z0 }
        for (hs in order) {
            p.color = hs.color
            wallQuad(pth, g, hs.side, hs.z0, hs.z1, 0f, hs.height)
            c.drawPath(pth, p)
            // shade towards the far end
            p.shader = LinearGradient(
                g.sx(hs.side * WALL_X, hs.z0), 0f, g.sx(hs.side * WALL_X, hs.z1), 0f,
                Color.TRANSPARENT, Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP
            )
            c.drawPath(pth, p)
            p.shader = null
            // parapet and plinth
            p.color = Color.argb(110, 0, 0, 0)
            wallQuad(pth, g, hs.side, hs.z0, hs.z1, hs.height - 0.22f, hs.height)
            c.drawPath(pth, p)
            wallQuad(pth, g, hs.side, hs.z0, hs.z1, 0f, 0.25f)
            c.drawPath(pth, p)
            // door
            val dz = hs.z0 + (hs.z1 - hs.z0) * 0.12f
            p.color = Color.parseColor("#5D4037")
            wallQuad(pth, g, hs.side, dz, dz + 0.45f, 0f, 0.95f)
            c.drawPath(pth, p)
            // a near edge line so houses read as separate blocks
            p.color = Color.argb(140, 0, 0, 0)
            p.strokeWidth = max(1f, g.scale(hs.z0) * 0.03f)
            c.drawLine(
                g.sx(hs.side * WALL_X, hs.z0), g.sy(0f, hs.z0),
                g.sx(hs.side * WALL_X, hs.z0), g.sy(hs.height, hs.z0), p
            )
        }
        // windows
        for (win in WINDOWS) {
            p.color = Color.parseColor("#3E2723")
            wallQuad(pth, g, win.side, win.z0 - 0.06f, win.z1 + 0.06f, win.y0 - 0.08f, win.y1 + 0.08f)
            c.drawPath(pth, p)
            p.color = Color.parseColor("#90CAF9")
            wallQuad(pth, g, win.side, win.z0, win.z1, win.y0, win.y1)
            c.drawPath(pth, p)
            p.color = Color.argb(120, 255, 255, 255) // glare
            wallQuad(pth, g, win.side, win.z0, win.z0 + (win.z1 - win.z0) * 0.3f, win.yc, win.y1)
            c.drawPath(pth, p)
        }

        // electricity cables sagging across the street
        p.style = Paint.Style.STROKE
        p.color = Color.argb(200, 20, 20, 20)
        for ((z, y) in listOf(3.1f to 3.7f, 7.8f to 3.6f)) {
            p.strokeWidth = max(1.5f, g.scale(z) * 0.025f)
            pth.reset()
            pth.moveTo(g.sx(-WALL_X, z), g.sy(y, z))
            pth.quadTo(g.sx(0f, z), g.sy(y - 0.9f, z), g.sx(WALL_X, z), g.sy(y, z))
            c.drawPath(pth, p)
        }
        p.style = Paint.Style.FILL
        return bmp
    }

    private fun wallQuad(p: Path, g: CricketGame, side: Int, z0: Float, z1: Float, y0: Float, y1: Float) {
        val x = side * WALL_X
        p.reset()
        p.moveTo(g.sx(x, z0), g.sy(y0, z0))
        p.lineTo(g.sx(x, z0), g.sy(y1, z0))
        p.lineTo(g.sx(x, z1), g.sy(y1, z1))
        p.lineTo(g.sx(x, z1), g.sy(y0, z1))
        p.close()
    }

    private fun groundQuad(p: Path, g: CricketGame, x0: Float, x1: Float, z0: Float, z1: Float) {
        p.reset()
        p.moveTo(g.sx(x0, z0), g.sy(0f, z0))
        p.lineTo(g.sx(x0, z1), g.sy(0f, z1))
        p.lineTo(g.sx(x1, z1), g.sy(0f, z1))
        p.lineTo(g.sx(x1, z0), g.sy(0f, z0))
        p.close()
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val g = game ?: return
        val scene = sceneBmp ?: return
        val now = SystemClock.uptimeMillis()
        val realDt = if (lastFrameAt == 0L) 0.016f else (now - lastFrameAt).coerceIn(1, 50) / 1000f
        lastFrameAt = now
        val b = bat

        g.update(now, b)
        val phase = g.phase
        val t = g.clock - g.phaseStart
        val dt = realDt * g.timeScale
        val w = width.toFloat()
        val h = height.toFloat()

        c.drawBitmap(scene, 0f, 0f, bitmapPaint)
        drawWindows(c, g, now)
        drawBowler(c, g)
        if (phase == Phase.RESULT && g.outcome == Outcome.CAUGHT) drawFielder(c, g)
        val ballBehindStumps = g.bz >= STUMPS_Z
        if (g.ballVisible && ballBehindStumps) drawBall(c, g)
        drawStumps(c, g)
        if (g.ballVisible && !ballBehindStumps) drawBall(c, g)
        if (sparksActive) drawSparks(c, dt)
        drawBat(c, b)
        drawHud(c, g, w)

        when (phase) {
            Phase.INTRO -> drawIntro(c, t, w, h)
            Phase.RUNUP -> drawChip(c, g.type.hint, w / 2f, barBottom + dp(58f), SOFT_WHITE)
            Phase.DELIVERY -> drawChip(c, deliveryStr, w / 2f, barBottom + dp(58f), GOLD)
            Phase.RESULT -> drawResult(c, g, w, h)
            Phase.LEVEL_CLEAR -> drawEnd(c, t, w, h, GOLD)
            Phase.GAME_OVER -> drawEnd(c, t, w, h, if (g.won || g.newBest) GOLD else LOSE_RED)
        }
        if (phase == Phase.RUNUP || phase == Phase.DELIVERY || phase == Phase.INTRO) drawHint(c, b, w, h)
        if (g.timeScale < 1f) drawSlowMo(c, w, h, now)
        if (confettiActive) drawConfetti(c, dt, h)

        if (phase != Phase.GAME_OVER || t < 3000 || confettiActive) postInvalidateOnAnimation()
    }

    // ---------- street details that change ----------

    private fun drawWindows(c: Canvas, g: CricketGame, now: Long) {
        for (i in WINDOWS.indices) {
            val win = WINDOWS[i]
            if (g.broken[i]) {
                fillPaint.color = Color.parseColor("#263238")
                wallQuad(path, g, win.side, win.z0, win.z1, win.y0, win.y1)
                c.drawPath(path, fillPaint)
                strokePaint.strokeWidth = max(1f, g.scale(win.zc) * 0.02f)
                strokePaint.color = Color.argb(200, 220, 240, 255)
                val cx = g.sx(win.side * WALL_X, win.zc)
                val cy = g.sy(win.yc, win.zc)
                val r = g.scale(win.zc) * 0.4f
                for (k in 0 until 5) {
                    val a = k * 1.3f + 0.4f
                    c.drawLine(cx, cy, cx + cos(a) * r * 0.6f, cy + sin(a) * r, strokePaint)
                }
            } else if (i == g.golden) {
                val pulse = 0.6f + 0.4f * sin(now / 180f)
                fillPaint.color = GOLD
                fillPaint.alpha = (150 + 100 * pulse).toInt()
                wallQuad(path, g, win.side, win.z0, win.z1, win.y0, win.y1)
                c.drawPath(path, fillPaint)
                strokePaint.strokeWidth = max(2f, g.scale(win.zc) * 0.05f)
                strokePaint.color = Color.argb((120 * pulse).toInt(), 255, 236, 140)
                wallQuad(path, g, win.side, win.z0 - 0.12f, win.z1 + 0.12f, win.y0 - 0.12f, win.y1 + 0.12f)
                c.drawPath(path, strokePaint)
            }
        }
    }

    // ---------- people ----------

    private fun drawBowler(c: Canvas, g: CricketGame) {
        val z = g.bowlerZ
        val running = g.phase == Phase.RUNUP
        val p = if (running) g.runupProgress else 1f
        val x = 0.42f - 0.14f * p // runs in at an angle towards the stumps
        val k = g.scale(z)
        val fx = g.sx(x, z)
        val fy = g.sy(0f, z)
        val hipY = fy - 0.95f * k
        val shY = fy - 1.5f * k
        val headY = fy - 1.74f * k
        val cycle = if (running) sin(p * g.type.runupMs / 1000f * 2f * PI.toFloat() * 2.2f) else 0f
        val stride = 0.28f * k

        // shadow
        fillPaint.color = Color.argb(70, 0, 0, 0)
        rect.set(fx - 0.35f * k, fy - 0.06f * k, fx + 0.35f * k, fy + 0.06f * k)
        c.drawOval(rect, fillPaint)

        strokePaint.strokeWidth = max(2f, 0.16f * k)
        strokePaint.color = Color.parseColor("#263238") // trousers
        c.drawLine(fx, hipY, fx + cycle * stride, fy, strokePaint)
        c.drawLine(fx, hipY, fx - cycle * stride, fy, strokePaint)

        strokePaint.strokeWidth = max(2.5f, 0.3f * k)
        strokePaint.color = Color.parseColor("#FF7043") // gully team T-shirt
        c.drawLine(fx, hipY, fx, shY, strokePaint)

        // arms: running swing, then the bowling arm windmills over the top
        strokePaint.strokeWidth = max(1.5f, 0.11f * k)
        strokePaint.color = SKIN
        val armLen = 0.62f * k
        c.drawLine(fx, shY, fx - cycle * 0.3f * k - 0.15f * k, shY + armLen * 0.85f, strokePaint)
        val action = ((p - 0.78f) / 0.22f).coerceIn(0f, 1f)
        val a = if (g.phase == Phase.RUNUP) PI.toFloat() * (0.85f - 1.7f * action) else -PI.toFloat() * 0.85f
        val hx = fx + sin(a) * armLen
        val hy = shY - cos(a) * armLen
        c.drawLine(fx, shY, hx, hy, strokePaint)
        if (g.phase == Phase.RUNUP) {
            fillPaint.color = BALL_RED
            c.drawCircle(hx, hy, max(2f, 0.07f * k), fillPaint)
        }

        fillPaint.color = SKIN
        c.drawCircle(fx, headY, 0.13f * k, fillPaint)
        fillPaint.color = Color.parseColor("#212121")
        c.drawCircle(fx, headY - 0.05f * k, 0.12f * k, fillPaint) // hair
    }

    private fun drawFielder(c: Canvas, g: CricketGame) {
        val z = g.fielderZ
        val k = g.scale(z)
        val fx = g.sx(g.fielderX, z)
        val fy = g.sy(0f, z)
        fillPaint.color = Color.argb(70, 0, 0, 0)
        rect.set(fx - 0.35f * k, fy - 0.06f * k, fx + 0.35f * k, fy + 0.06f * k)
        c.drawOval(rect, fillPaint)
        strokePaint.strokeWidth = max(2f, 0.16f * k)
        strokePaint.color = Color.parseColor("#263238")
        c.drawLine(fx, fy - 0.95f * k, fx - 0.2f * k, fy, strokePaint)
        c.drawLine(fx, fy - 0.95f * k, fx + 0.2f * k, fy, strokePaint)
        strokePaint.strokeWidth = max(2.5f, 0.3f * k)
        strokePaint.color = Color.parseColor("#42A5F5")
        c.drawLine(fx, fy - 0.95f * k, fx, fy - 1.5f * k, strokePaint)
        strokePaint.strokeWidth = max(1.5f, 0.11f * k)
        strokePaint.color = SKIN
        c.drawLine(fx, fy - 1.5f * k, fx - 0.3f * k, fy - 2.05f * k, strokePaint) // arms up for the catch
        c.drawLine(fx, fy - 1.5f * k, fx + 0.3f * k, fy - 2.05f * k, strokePaint)
        fillPaint.color = SKIN
        c.drawCircle(fx, fy - 1.74f * k, 0.13f * k, fillPaint)
    }

    // ---------- ball + stumps ----------

    private fun drawBall(c: Canvas, g: CricketGame) {
        val k = g.scale(g.bz)
        val r = max(dp(2.5f), CricketGame.BALL_R * 2f * k)
        // shadow on the road, smaller and fainter the higher the ball is
        val shY = g.sy(0f, g.bz)
        val lift = (g.by / 3f).coerceIn(0f, 1f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (90 * (1f - lift * 0.7f)).toInt()
        val sw = r * (1.3f - 0.5f * lift)
        rect.set(g.sx(g.bx, g.bz) - sw, shY - sw * 0.35f, g.sx(g.bx, g.bz) + sw, shY + sw * 0.35f)
        c.drawOval(rect, fillPaint)

        val x = g.sx(g.bx, g.bz)
        val y = g.sy(g.by, g.bz) - r
        fillPaint.color = BALL_DARK
        fillPaint.alpha = 255
        c.drawCircle(x, y, r, fillPaint)
        fillPaint.color = BALL_RED
        c.drawCircle(x - r * 0.08f, y - r * 0.08f, r * 0.88f, fillPaint)
        if (r > dp(5f)) {
            strokePaint.strokeWidth = max(1f, r * 0.12f)
            strokePaint.color = Color.argb(200, 255, 255, 255)
            val spin = (g.clock / 60.0).toFloat()
            rect.set(x - r * 0.7f, y - r * 0.7f, x + r * 0.7f, y + r * 0.7f)
            c.drawArc(rect, spin * 57f, 120f, false, strokePaint) // seam
        }
        fillPaint.color = Color.argb(150, 255, 255, 255)
        c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.25f, fillPaint)
    }

    private fun drawStumps(c: Canvas, g: CricketGame) {
        val z = STUMPS_Z
        val k = g.scale(z)
        val baseY = g.sy(0f, z)
        val sw = 0.035f * k
        val hgt = STUMP_H * k
        val bowled = g.outcome == Outcome.BOWLED && (g.phase == Phase.RESULT)
        val tHit = if (bowled) ((g.clock - g.resolvedAt) / 1000.0).toFloat() else 0f
        val fall = (tHit / 0.18f).coerceIn(0f, 1f)

        for (i in -1..1) {
            val x = g.sx(i * STUMP_HALF, z)
            c.save()
            if (bowled) c.rotate(fall * STUMP_FALL[i + 1], x, baseY)
            fillPaint.color = Color.argb(60, 0, 0, 0)
            c.drawRect(x - sw + sw * 0.6f, baseY - hgt, x + sw + sw * 0.6f, baseY, fillPaint)
            fillPaint.color = STUMP
            rect.set(x - sw, baseY - hgt, x + sw, baseY)
            c.drawRoundRect(rect, sw, sw, fillPaint)
            fillPaint.color = Color.argb(60, 0, 0, 0)
            c.drawRect(x + sw * 0.2f, baseY - hgt, x + sw, baseY, fillPaint)
            c.restore()
        }
        // bails: sit on top, or fly off when bowled
        val topY = baseY - hgt
        fillPaint.color = STUMP
        for (j in 0..1) {
            val x0 = g.sx(-STUMP_HALF + j * STUMP_HALF, z)
            val bw = STUMP_HALF * k * 0.95f
            if (!bowled) {
                rect.set(x0 - bw * 0.05f, topY - sw * 0.9f, x0 + bw, topY)
                c.drawRoundRect(rect, sw * 0.4f, sw * 0.4f, fillPaint)
            } else if (tHit < 1.4f) {
                val vx = (if (j == 0) -1.3f else 1.6f) * k
                val vy = -2.6f * k
                val bxp = x0 + vx * tHit
                val byp = topY + vy * tHit + 4.5f * k * tHit * tHit
                c.save()
                c.rotate(tHit * (if (j == 0) -720f else 900f), bxp + bw / 2f, byp)
                rect.set(bxp, byp - sw * 0.45f, bxp + bw, byp + sw * 0.45f)
                c.drawRoundRect(rect, sw * 0.4f, sw * 0.4f, fillPaint)
                c.restore()
            }
        }
    }

    // ---------- the bat (your hand) ----------

    private fun drawBat(c: Canvas, b: BatState?) {
        val pts = b?.hand ?: return
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = Color.argb(60, 255, 255, 255)
        for ((i, j) in HandSkeleton.BONES) c.drawLine(pts[i].x, pts[i].y, pts[j].x, pts[j].y, strokePaint)

        val gx = b.gripX
        val gy = b.gripY
        val tx = b.tipX
        val ty = b.tipY
        val len = hypot(tx - gx, ty - gy)
        if (len < 4f) return

        // swing trail
        trailX[trailPos] = tx
        trailY[trailPos] = ty
        trailPos = (trailPos + 1) % trailN
        trailCount = min(trailCount + 1, trailN)
        if (b.speed > BatTracker.SWING_ON * 0.6f && trailCount > 2) {
            for (i in 1 until trailCount) {
                val a = (trailPos - i + trailN) % trailN
                val p = (trailPos - i - 1 + trailN) % trailN
                strokePaint.strokeWidth = b.width * 0.5f * (1f - i / trailN.toFloat())
                strokePaint.color = Color.argb((110 * (1f - i / trailN.toFloat())).toInt(), 255, 255, 255)
                c.drawLine(trailX[a], trailY[a], trailX[p], trailY[p], strokePaint)
            }
        }

        val ang = Math.toDegrees(atan2((ty - gy).toDouble(), (tx - gx).toDouble())).toFloat()
        val bw = b.width
        c.save()
        c.translate(gx, gy)
        c.rotate(ang)
        // handle with grip rings
        val handle = len * 0.3f
        fillPaint.color = Color.parseColor("#212121")
        rect.set(-len * 0.08f, -bw * 0.16f, handle, bw * 0.16f)
        c.drawRoundRect(rect, bw * 0.16f, bw * 0.16f, fillPaint)
        strokePaint.strokeWidth = max(1f, bw * 0.05f)
        strokePaint.color = Color.parseColor("#E53935")
        var gxp = 0f
        while (gxp < handle) {
            c.drawLine(gxp, -bw * 0.16f, gxp, bw * 0.16f, strokePaint)
            gxp += bw * 0.3f
        }
        // blade
        fillPaint.color = Color.parseColor("#5D4037")
        rect.set(handle - dp(1.5f), -bw / 2f - dp(1.5f), len + dp(1.5f), bw / 2f + dp(1.5f))
        c.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, fillPaint)
        fillPaint.color = WILLOW
        rect.set(handle, -bw / 2f, len, bw / 2f)
        c.drawRoundRect(rect, bw * 0.28f, bw * 0.28f, fillPaint)
        fillPaint.color = Color.argb(70, 255, 255, 255)
        rect.set(handle, -bw / 2f, len, -bw * 0.15f)
        c.drawRoundRect(rect, bw * 0.2f, bw * 0.2f, fillPaint)
        fillPaint.color = Color.parseColor("#1565C0") // sticker
        rect.set(handle + (len - handle) * 0.35f, -bw * 0.3f, handle + (len - handle) * 0.55f, bw * 0.3f)
        c.drawRect(rect, fillPaint)
        c.restore()
    }

    // ---------- HUD ----------

    private fun drawHud(c: Canvas, g: CricketGame, w: Float) {
        val top = barTop
        rect.set(dp(16f), top, w - dp(16f), barBottom)
        fillPaint.color = Color.argb(215, 14, 14, 24)
        c.drawRoundRect(rect, dp(22f), dp(22f), fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(rect, dp(22f), dp(22f), strokePaint)

        val sKey = g.runs * 100 + g.wickets
        if (sKey != scoreFor) {
            scoreFor = sKey
            scoreStr = "${g.runs}/${g.wickets}"
        }
        val chase = g.mode == CricketMode.CHASE
        val sideKey = if (chase) g.ballsLeft else g.balls
        if (sideKey != sideFor) {
            sideFor = sideKey
            sideStr = if (chase) g.ballsLeft.toString() else "${g.balls / 6}.${g.balls % 6}"
        }
        val cKey = if (chase) (g.target - g.runs) * 1000 + g.ballsLeft else g.best
        if (cKey != centerFor) {
            centerFor = cKey
            centerStr = if (chase) {
                val need = max(0, g.target - g.runs)
                if (need == 0) "Target reached!" else "Need $need off ${g.ballsLeft}"
            } else "BEST ${g.best}"
        }

        text(c, "SCORE", w * 0.18f, top + dp(22f), sp(11f), MUTED, outline = false)
        text(c, scoreStr, w * 0.18f, top + dp(50f), sp(26f), GOLD, outline = false)
        text(c, if (chase) "BALLS" else "OVERS", w * 0.82f, top + dp(22f), sp(11f), MUTED, outline = false)
        text(c, sideStr, w * 0.82f, top + dp(50f), sp(26f), Color.WHITE, outline = false)
        text(c, titleStr, w / 2f, top + dp(20f), sp(12f), Color.WHITE, outline = false)
        text(c, centerStr, w / 2f, top + dp(44f), sp(15f), if (chase) WIN_GREEN else MUTED, outline = false)

        // this over, ball by ball
        val spacing = dp(24f)
        val startX = w / 2f - spacing * 2.5f
        val py = top + dp(66f)
        for (i in 0 until 6) {
            val x = startX + i * spacing
            val v = g.pips[i]
            if (v < 0) {
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.argb(90, 255, 255, 255)
                c.drawCircle(x, py, dp(8f), strokePaint)
                continue
            }
            fillPaint.color = when {
                v == CricketGame.PIP_OUT -> LOSE_RED
                v >= 6 -> GOLD
                v == 4 -> WIN_GREEN
                v == 0 -> Color.argb(120, 255, 255, 255)
                else -> Color.parseColor("#4FC3F7")
            }
            c.drawCircle(x, py, dp(9f), fillPaint)
            val label = if (v == CricketGame.PIP_OUT) "W" else PIP_TEXT[min(v, PIP_TEXT.size - 1)]
            text(c, label, x, py, sp(10f), Color.parseColor("#141414"), outline = false)
        }
    }

    // ---------- overlays ----------

    private fun drawIntro(c: Canvas, t: Double, w: Float, h: Float) {
        val cy = h * 0.5f
        val fadeOut = if (t > CricketGame.INTRO_MS - 300) ((CricketGame.INTRO_MS - t) / 300.0).toFloat() else 1f
        val alpha = (255 * fadeOut.coerceIn(0f, 1f)).toInt()
        fillPaint.color = Color.BLACK
        fillPaint.alpha = alpha * 130 / 255
        c.drawRect(0f, cy - dp(78f), w, cy + dp(66f), fillPaint)
        val p = (t / 450.0).toFloat().coerceAtMost(1f)
        text(c, introTitle, w / 2f, cy - dp(36f), sp(38f), GOLD, alpha, 0.6f + 0.4f * easeOutBack(p))
        val p2 = ((t - 200) / 400.0).toFloat().coerceIn(0f, 1f)
        text(c, introLine1, w / 2f, cy + dp(8f), sp(20f), Color.WHITE, (alpha * p2).toInt())
        text(c, introLine2, w / 2f, cy + dp(38f), sp(14f), SOFT_WHITE, (alpha * p2).toInt())
    }

    private fun drawResult(c: Canvas, g: CricketGame, w: Float, h: Float) {
        if (g.clock < textAt) return
        val t = (g.clock - textAt).toFloat()
        val p = (t / 380f).coerceAtMost(1f)
        val y = h * 0.25f // up in the sky, clear of the bowler and fielders
        val size = when (g.outcome) {
            Outcome.SIX -> sp(64f)
            Outcome.FOUR -> sp(52f)
            Outcome.WINDOW_OUT, Outcome.GOLDEN, Outcome.CAUGHT_BEHIND -> sp(30f)
            else -> sp(40f)
        }
        textPaint.textSize = size
        val fit = min(1f, (w - dp(40f)) / textPaint.measureText(bigStr)) // long ones shrink to fit
        text(c, bigStr, w / 2f, y, size * fit, bigColor, 255, 0.5f + 0.5f * easeOutBack(p))
        val p2 = ((t - 150f) / 300f).coerceIn(0f, 1f)
        if (subStr.isNotEmpty()) text(c, subStr, w / 2f, y + size * 0.75f, sp(15f), Color.WHITE, (255 * p2).toInt())
        if (timingStr.isNotEmpty()) {
            val col = if (timingStr === TIMING_PERFECT) WIN_GREEN else SOFT_WHITE
            drawChip(c, timingStr, w / 2f, y + size * 0.75f + dp(34f), col, (255 * p2).toInt())
        }
    }

    private fun drawEnd(c: Canvas, t: Double, w: Float, h: Float, color: Int) {
        val p = (t / 400.0).toFloat().coerceAtMost(1f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (p * 170).toInt()
        c.drawRect(0f, 0f, w, h, fillPaint)
        val cy = h * 0.42f
        text(c, endTitle, w / 2f, cy - dp(30f), sp(34f), color, 255, easeOutBack(p))
        val p2 = ((t - 250) / 400.0).toFloat().coerceIn(0f, 1f)
        text(c, endLine1, w / 2f, cy + dp(16f), sp(18f), Color.WHITE, (p2 * 255).toInt())
        text(c, endLine2, w / 2f, cy + dp(46f), sp(15f), if (endLine2 == "NEW BEST!") GOLD else SOFT_WHITE, (p2 * 255).toInt())
    }

    private fun drawSlowMo(c: Canvas, w: Float, h: Float, now: Long) {
        fillPaint.color = Color.argb(200, 0, 0, 0)
        c.drawRect(0f, 0f, w, h * 0.05f, fillPaint)
        c.drawRect(0f, h * 0.95f, w, h, fillPaint)
        val a = (170 + 80 * sin(now / 150f)).toInt()
        text(c, "◉ SLOW-MO", w - dp(70f), h * 0.975f, sp(13f), LOSE_RED, a, outline = false)
    }

    private fun drawHint(c: Canvas, b: BatState?, w: Float, h: Float) {
        val (msg, col) = if (b?.hand == null) "Show your hand to the camera ✋" to SOFT_WHITE
        else "Swing when the ball reaches you!" to SOFT_WHITE
        drawChip(c, msg, w / 2f, h - dp(40f), col)
    }

    // ---------- shared widgets ----------

    private fun drawChip(c: Canvas, s: String, cx: Float, cy: Float, color: Int, alpha: Int = 255) {
        if (alpha <= 0 || s.isEmpty()) return
        val size = sp(14f)
        textPaint.textSize = size
        val half = textPaint.measureText(s) / 2f + dp(14f)
        val hh = size * 0.62f + dp(6f)
        rect.set(cx - half, cy - hh, cx + half, cy + hh)
        fillPaint.color = Color.argb(185 * alpha / 255, 0, 0, 0)
        c.drawRoundRect(rect, hh, hh, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (color == SOFT_WHITE) Color.argb(80, 255, 255, 255) else color
        strokePaint.alpha = strokePaint.alpha * alpha / 255
        c.drawRoundRect(rect, hh, hh, strokePaint)
        text(c, s, cx, cy, size, color, alpha, outline = false)
    }

    /** Centered text with an optional dark outline so it reads over any background. */
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

    // ---------- particles ----------

    private fun spawnSparks(x: Float, y: Float, count: Int, gold: Boolean) {
        for (i in 0 until min(count, sparkN)) {
            val ang = Random.nextFloat() * 2f * PI.toFloat()
            val speed = dp(60f) + Random.nextFloat() * dp(220f)
            sX[i] = x
            sY[i] = y
            sVx[i] = cos(ang) * speed
            sVy[i] = sin(ang) * speed - dp(80f)
            sLife[i] = 0.6f + Random.nextFloat() * 0.6f
            sShard[i] = !gold
            sColor[i] = if (gold) CONFETTI_COLORS[Random.nextInt(2)] else if (i % 3 == 0) Color.WHITE else GLASS
        }
        for (i in min(count, sparkN) until sparkN) sLife[i] = 0f
        sparksActive = true
    }

    private fun drawSparks(c: Canvas, dt: Float) {
        var alive = false
        for (i in 0 until sparkN) {
            if (sLife[i] <= 0f) continue
            sLife[i] -= dt
            sVy[i] += dp(700f) * dt
            sX[i] += sVx[i] * dt
            sY[i] += sVy[i] * dt
            if (sLife[i] <= 0f) continue
            alive = true
            fillPaint.color = sColor[i]
            fillPaint.alpha = (255 * min(1f, sLife[i] / 0.3f)).toInt()
            if (sShard[i]) {
                // a little spinning triangle of glass
                val r = dp(5f)
                val a = sLife[i] * 9f + i
                path.reset()
                path.moveTo(sX[i] + cos(a) * r, sY[i] + sin(a) * r)
                path.lineTo(sX[i] + cos(a + 2.4f) * r * 0.7f, sY[i] + sin(a + 2.4f) * r * 0.7f)
                path.lineTo(sX[i] + cos(a + 4.1f) * r * 0.5f, sY[i] + sin(a + 4.1f) * r * 0.5f)
                path.close()
                c.drawPath(path, fillPaint)
            } else {
                c.drawCircle(sX[i], sY[i], dp(3f), fillPaint)
            }
        }
        sparksActive = alive
    }

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

    // ---------- easing ----------

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private companion object {
        const val TIMING_PERFECT = "Perfect timing"
        val PIP_TEXT = arrayOf("•", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
        val STUMP_FALL = floatArrayOf(-38f, 12f, 44f)
        val GOLD = Color.parseColor("#FFD54F")
        val WIN_GREEN = Color.parseColor("#69F0AE")
        val LOSE_RED = Color.parseColor("#FF5252")
        val MUTED = Color.parseColor("#B0B3C6")
        val SOFT_WHITE = Color.parseColor("#E6FFFFFF")
        val SKIN = Color.parseColor("#C68642")
        val BALL_RED = Color.parseColor("#C62828")
        val BALL_DARK = Color.parseColor("#6D1010")
        val STUMP = Color.parseColor("#F5E6C8")
        val WILLOW = Color.parseColor("#E9C891")
        val GLASS = Color.parseColor("#B3E5FC")
        val CONFETTI_COLORS = intArrayOf(
            Color.parseColor("#FFD54F"), Color.parseColor("#FFF59D"), Color.parseColor("#4FC3F7"),
            Color.parseColor("#FF8A80"), Color.parseColor("#69F0AE"), Color.WHITE
        )
    }
}

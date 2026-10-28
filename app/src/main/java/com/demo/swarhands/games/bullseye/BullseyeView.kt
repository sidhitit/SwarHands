package com.demo.swarhands.games.bullseye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import com.demo.swarhands.games.bullseye.BullseyeGame.Outcome
import com.demo.swarhands.games.bullseye.BullseyeGame.Phase
import com.demo.swarhands.gesture.HandSkeleton
import com.demo.swarhands.gesture.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the Bullseye range over the camera preview.
 *
 * Lightweight by design:
 *  - everything is plain shapes; only the target face is a pre-rendered bitmap
 *  - paints, paths, particles and strings are allocated once, never inside onDraw
 *  - redraws at display rate only while something moves; otherwise at the camera's ~30 fps
 */
class BullseyeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), BullseyeGame.Listener {

    var sfx: SfxPlayer? = null
    var onPhaseChanged: ((Phase) -> Unit)? = null
    var game: BullseyeGame? = null
        private set

    /** Weapon position, read by the tracking thread: the slingshot fork, or the bow's line. */
    @Volatile var anchorX = 0f
        private set
    @Volatile var anchorY = 0f
        private set
    @Volatile private var aim: AimState? = null

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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val topScrim = Paint()
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val lanePath = Path()
    private val starPath = Path()
    private val tmpPath = Path()
    private val painter = WeaponPainter(dpF)

    // ---------- layout ----------
    private var targetBmp: Bitmap? = null
    private var slingSize = 0f
    private var bowSize = 0f
    private val barTop get() = dp(14f)
    private val barBottom get() = dp(90f)

    // ---------- projectile scratch (set by [projectileAt]) ----------
    private var projX = 0f
    private var projY = 0f
    private var projZ = 1f

    // ---------- sparks (preallocated pool) ----------
    private val sparkN = 36
    private val sX = FloatArray(sparkN)
    private val sY = FloatArray(sparkN)
    private val sVx = FloatArray(sparkN)
    private val sVy = FloatArray(sparkN)
    private val sLife = FloatArray(sparkN)
    private val sColor = IntArray(sparkN)
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

    // ---------- wind streaks ----------
    private val windN = 8
    private val wX = FloatArray(windN)
    private val wY = FloatArray(windN)
    private val wLen = FloatArray(windN)

    // ---------- cached strings ----------
    private var scoreStr = "0"
    private var scoreFor = -1
    private var bestStr = "BEST 0"
    private var bestFor = -1
    private var stageStr = "STAGE 1"
    private var stageClearStr = ""
    private var stagePtsStr = ""
    private var nextStageStr = ""
    private var streakStr = ""
    private var lastFrameAt = 0L

    // =====================================================================
    // Public API
    // =====================================================================

    fun start(weapon: Weapon, best: Int) {
        game = BullseyeGame(weapon, best, this).also {
            if (width > 0) {
                it.layout(width.toFloat(), height.toFloat(), dpF)
                updateAnchor(it)
            }
        }
        invalidate()
    }

    private fun updateAnchor(g: BullseyeGame) {
        anchorX = g.slingX
        // the bow's fixed line sits a little higher so there is room to pull the string down
        anchorY = if (g.weapon == Weapon.BOW) g.h * 0.68f else g.slingY
    }

    /** Thread-safe: called from the tracking thread. */
    fun transformFor(imgW: Int, imgH: Int): ViewTransform? {
        val w = width
        val h = height
        if (w == 0 || h == 0) return null
        return ViewTransform(w, h, imgW, imgH)
    }

    /** Thread-safe: called from the tracking thread. */
    fun submitAim(state: AimState) {
        aim = state
        postInvalidate()
    }

    fun restartGame() {
        game?.restart(SystemClock.uptimeMillis())
        confettiActive = false
        invalidate()
    }

    // =====================================================================
    // BullseyeGame.Listener (UI thread, from game.update)
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
            Phase.INTRO -> stageStr = "STAGE ${g.stageIndex + 1}"
            Phase.STAGE_CLEAR -> {
                stageClearStr = "STAGE ${g.stageIndex + 1} CLEAR!"
                stagePtsStr = "+${g.stageScore} points"
                nextStageStr = "Next: ${BullseyeGame.STAGES[g.stageIndex + 1].title}"
            }
            Phase.GAME_OVER -> if (g.newBest || g.stars == 3) spawnConfetti()
            else -> Unit
        }
        post { onPhaseChanged?.invoke(phase) } // never touch other views mid-draw
    }

    override fun onLanded() {
        val g = game ?: return
        streakStr = if (g.streak >= 2) "🔥 ${g.streak} IN A ROW" else ""
        if (g.outcome != Outcome.HIT) return
        val now = SystemClock.uptimeMillis()
        val hx = g.targetX(now) + g.hitOx * g.radius
        val hy = g.targetY + g.hitOy * g.radius
        val gold = g.points == 10
        spawnSparks(hx, hy, if (gold) 36 else 16, gold)
    }

    // =====================================================================
    // Layout
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val g = game
        val layout = g ?: BullseyeGame(Weapon.SLINGSHOT, 0, this)
        layout.layout(w.toFloat(), h.toFloat(), dpF)
        slingSize = min(dp(70f), h * 0.075f)
        bowSize = min(dp(95f), w * AimTracker.RAIL_MIN * 0.95f)
        updateAnchor(layout)
        targetBmp = renderTarget(layout.radius)

        topScrim.shader = LinearGradient(
            0f, 0f, 0f, h * 0.22f, Color.argb(170, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        // ground lane in perspective, from the bottom edge to past the target
        val far = BullseyeGame.DEPTH * 1.6f
        val half = w * 0.75f
        lanePath.reset()
        lanePath.moveTo(w / 2f - half, layout.groundY(1f))
        lanePath.lineTo(w / 2f - half / far, layout.groundY(far))
        lanePath.lineTo(w / 2f + half / far, layout.groundY(far))
        lanePath.lineTo(w / 2f + half, layout.groundY(1f))
        lanePath.close()
        lanePaint.shader = LinearGradient(
            0f, layout.groundY(far), 0f, h.toFloat(),
            Color.argb(20, 105, 240, 174), Color.argb(95, 46, 125, 50), Shader.TileMode.CLAMP
        )

        // 5-point star, outer radius 1 (scaled when drawn)
        starPath.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 1f else 0.45f
            val a = -PI / 2 + i * PI / 5
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()

        for (i in 0 until windN) {
            wX[i] = Random.nextFloat() * w
            wY[i] = h * (0.2f + Random.nextFloat() * 0.55f)
            wLen[i] = dp(30f) + Random.nextFloat() * dp(50f)
        }
    }

    private fun renderTarget(r: Float): Bitmap {
        val size = (r * 2.3f).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = STRAW_DARK
        c.drawCircle(cx, cx, r * 1.12f, p)
        p.color = STRAW
        c.drawCircle(cx, cx, r * 1.08f, p)
        for (i in 0 until 5) {
            p.color = RING_COLORS[i]
            c.drawCircle(cx, cx, r * (1f - i * 0.2f), p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, r * 0.012f)
        for (i in 0 until 5) {
            p.color = if (i == 2) Color.argb(120, 255, 255, 255) else Color.argb(90, 0, 0, 0)
            c.drawCircle(cx, cx, r * (1f - i * 0.2f), p)
        }
        p.color = Color.argb(150, 0, 0, 0)
        c.drawLine(cx - r * 0.05f, cx, cx + r * 0.05f, cx, p)
        c.drawLine(cx, cx - r * 0.05f, cx, cx + r * 0.05f, p)
        // soft light from the top-left
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx - r * 0.4f, cx - r * 0.4f, r * 1.6f,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Color.argb(80, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cx, r * 1.12f, p)
        return bmp
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val g = game ?: return
        if (targetBmp == null) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else (now - lastFrameAt).coerceIn(1, 50) / 1000f
        lastFrameAt = now
        val a = aim

        g.update(now, a)
        val phase = g.phase
        val t = now - g.phaseStart
        val w = width.toFloat()
        val h = height.toFloat()
        val tx = g.targetX(now)

        c.drawRect(0f, 0f, w, h * 0.22f, topScrim)
        c.drawPath(lanePath, lanePaint)
        if (g.stage.windy) drawWind(c, g, dt, w, now)
        drawTarget(c, g, tx, now)
        drawStuck(c, g, tx)
        if (phase == Phase.FLIGHT || phase == Phase.RESULT) drawProjectile(c, g, t, tx)
        drawHands(c, a)
        drawWeapon(c, g, a, now)
        if (sparksActive) drawSparks(c, dt)
        drawHud(c, g, now, w)

        when (phase) {
            Phase.INTRO -> drawIntro(c, g, t, w, h)
            Phase.RESULT -> drawResult(c, g, t, tx)
            Phase.STAGE_CLEAR -> drawStageClear(c, t, w, h)
            Phase.GAME_OVER -> drawGameOver(c, g, now, t, w, h)
            else -> Unit
        }
        if (phase == Phase.AIM) drawHint(c, g, a, w, h, now)
        if (confettiActive) drawConfetti(c, dt, h)

        if (needsAnimation(g, phase, t, a)) postInvalidateOnAnimation()
    }

    private fun needsAnimation(g: BullseyeGame, phase: Phase, t: Long, a: AimState?): Boolean = when (phase) {
        Phase.INTRO, Phase.FLIGHT, Phase.RESULT, Phase.STAGE_CLEAR -> true
        Phase.AIM -> g.stage.moving || g.stage.windy || a?.drawing == true || sparksActive || g.shake > 0.01f ||
            g.weapon == Weapon.BOW // the pinch dot pulses
        Phase.GAME_OVER -> t < 3000 || confettiActive
    }

    // ---------- range ----------

    private fun drawTarget(c: Canvas, g: BullseyeGame, tx: Float, now: Long) {
        val r = g.radius
        val ty = g.targetY
        // shadow + legs
        fillPaint.color = Color.argb(70, 0, 0, 0)
        rect.set(tx - r * 1.1f, g.footY - r * 0.12f, tx + r * 1.1f, g.footY + r * 0.12f)
        c.drawOval(rect, fillPaint)
        strokePaint.strokeWidth = r * 0.09f
        strokePaint.color = LEG_DARK
        c.drawLine(tx, ty, tx, g.footY - r * 0.1f, strokePaint)
        strokePaint.color = LEG
        c.drawLine(tx - r * 0.45f, ty + r * 0.6f, tx - r * 0.85f, g.footY, strokePaint)
        c.drawLine(tx + r * 0.45f, ty + r * 0.6f, tx + r * 0.85f, g.footY, strokePaint)

        if (g.stage.windy) {
            // wind flag on a pole beside the target
            val px = tx + r * 1.35f
            val top = ty - r * 1.0f
            strokePaint.strokeWidth = dp(2.5f)
            strokePaint.color = Color.WHITE
            c.drawLine(px, g.footY, px, top, strokePaint)
            val dir = if (g.wind >= 0f) 1f else -1f
            val len = r * (0.25f + 0.45f * abs(g.wind))
            val flap = sin(now / 70f) * r * 0.05f * (0.5f + abs(g.wind))
            tmpPath.reset()
            tmpPath.moveTo(px, top)
            tmpPath.lineTo(px + dir * len, top + r * 0.12f + flap)
            tmpPath.lineTo(px, top + r * 0.26f)
            tmpPath.close()
            fillPaint.color = FLAG
            c.drawPath(tmpPath, fillPaint)
        }

        val bmp = targetBmp ?: return
        c.drawBitmap(bmp, tx - bmp.width / 2f, ty - bmp.height / 2f, bitmapPaint)
    }

    private fun drawStuck(c: Canvas, g: BullseyeGame, tx: Float) {
        val r = g.radius
        for (i in 0 until g.stuckCount) {
            val hx = tx + g.stuckX[i] * r
            val hy = g.targetY + g.stuckY[i] * r
            if (g.weapon == Weapon.BOW) {
                fillPaint.color = Color.argb(200, 20, 10, 5)
                c.drawCircle(hx, hy, r * 0.035f, fillPaint)
                painter.arrow(c, hx + g.stuckTilt[i] * r * 0.8f, hy + r * 0.5f, hx, hy, r * 0.06f, 255)
            } else {
                // dent with a little crack
                fillPaint.color = Color.argb(170, 40, 25, 10)
                c.drawCircle(hx, hy, r * 0.06f, fillPaint)
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = Color.argb(160, 40, 25, 10)
                for (k in 0 until 4) {
                    val ang = k * 1.7f + i
                    c.drawLine(hx, hy, hx + cos(ang) * r * 0.14f, hy + sin(ang) * r * 0.14f, strokePaint)
                }
            }
        }
    }

    private fun drawWind(c: Canvas, g: BullseyeGame, dt: Float, w: Float, now: Long) {
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = Color.argb(55, 255, 255, 255)
        val speed = g.wind * dp(520f)
        for (i in 0 until windN) {
            wX[i] += speed * dt
            if (wX[i] > w + wLen[i]) wX[i] = -wLen[i]
            if (wX[i] < -wLen[i]) wX[i] = w + wLen[i]
            val wob = sin(now / 300f + i) * dp(3f)
            c.drawLine(wX[i], wY[i] + wob, wX[i] - wLen[i] * (if (g.wind >= 0f) 1f else -1f), wY[i] + wob, strokePaint)
        }
    }

    // ---------- projectile ----------

    /** Fake-3D flight: linear in depth, projected so it slows and shrinks into the distance. */
    private fun projectileAt(g: BullseyeGame, u: Float) {
        val d = g.flightDepth
        val z = 1f + (d - 1f) * u
        val s = (1f - 1f / z) / (1f - 1f / d)
        projX = g.fromX + (g.toX - g.fromX) * s
        projY = g.fromY + (g.toY - g.fromY) * s - g.arc * sin(PI.toFloat() * min(s, 1f))
        projZ = z
    }

    private fun drawProjectile(c: Canvas, g: BullseyeGame, t: Long, tx: Float) {
        var u: Float
        var alpha = 255
        if (g.phase == Phase.FLIGHT) {
            u = (t.toFloat() / g.flightMs).coerceIn(0f, 1f)
        } else if (g.flyOn) {
            u = 1f + t.toFloat() / g.flightMs
            alpha = (255 * (1f - (u - 1f) / 0.6f)).toInt()
            if (alpha <= 0) return
        } else {
            if (g.outcome == Outcome.HIT) {
                // arrows stay in the target (drawn by drawStuck); stones drop to the ground
                if (g.weapon == Weapon.SLINGSHOT) {
                    val sec = t / 1000f
                    val hx = tx + g.hitOx * g.radius
                    val y = min(g.targetY + g.hitOy * g.radius + 0.5f * dp(2000f) * sec * sec, g.footY)
                    painter.stone(c, hx, y, dp(4f))
                }
                return
            }
            u = 1f
        }

        projectileAt(g, u)
        val x = projX
        val y = projY
        val z = projZ
        val scale = 1f / z

        // ground shadow sells the depth
        val gy = g.groundY(z)
        fillPaint.color = Color.argb(alpha * 80 / 255, 0, 0, 0)
        val sr = dp(14f) * scale
        rect.set(x - sr, gy - sr * 0.3f, x + sr, gy + sr * 0.3f)
        c.drawOval(rect, fillPaint)

        if (g.weapon == Weapon.BOW) {
            projectileAt(g, (u - 0.05f).coerceAtLeast(0f))
            var dx = x - projX
            var dy = y - projY
            val len = hypot(dx, dy)
            if (len < 0.01f) { dx = 0f; dy = -1f } else { dx /= len; dy /= len }
            val l = dp(120f) * scale
            painter.arrow(c, x - dx * l, y - dy * l, x, y, dp(10f) * scale, alpha)
        } else {
            painter.stone(c, x, y, dp(13f) * (0.25f + 0.75f * scale))
        }
    }

    // ---------- hands + weapon ----------

    private fun drawHands(c: Canvas, a: AimState?) {
        val hands = a?.hands ?: return
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = Color.argb(70, 255, 255, 255)
        for (pts in hands) {
            if (pts.size < 21) continue
            for ((i, j) in HandSkeleton.BONES) c.drawLine(pts[i].x, pts[i].y, pts[j].x, pts[j].y, strokePaint)
        }
        fillPaint.color = Color.argb(150, 79, 195, 247)
        for (pts in hands) {
            if (pts.size < 21) continue
            c.drawCircle(pts[4].x, pts[4].y, dp(4f), fillPaint)
            c.drawCircle(pts[8].x, pts[8].y, dp(4f), fillPaint)
        }
    }

    private fun drawWeapon(c: Canvas, g: BullseyeGame, a: AimState?, now: Long) {
        val drawing = a?.drawing == true && g.phase == Phase.AIM
        val power = if (drawing) a!!.power else 0f
        val amp = g.shake * dp(8f)
        val jx = (sin(now / 23f) + 0.5f * sin(now / 9f)) * amp
        val jy = (cos(now / 19f) + 0.5f * sin(now / 13f)) * amp * 0.7f
        c.save()
        c.translate(jx, jy)

        if (g.weapon == Weapon.SLINGSHOT) {
            val s = slingSize
            val px = if (drawing) a!!.pullX else anchorX
            val py = if (drawing) a!!.pullY else anchorY + s * 0.05f
            if (drawing && power > 0.15f) drawGuide(c, g, anchorX, anchorY, a!!.dirX, a.dirY, power)
            painter.slingshot(c, anchorX, anchorY, s, px, py, power, g.phase == Phase.AIM, power > 1f, now)
        } else {
            // bow on its fixed line, tilted by the fingers; the string rests NOCK_REST behind the grip
            val gx = a?.gripX ?: anchorX
            val gy = anchorY
            val dx = a?.dirX ?: 0f
            val dy = a?.dirY ?: -1f
            val rest = bowSize * NOCK_REST
            drawAxis(c, gy)
            if (drawing && power > 0.15f) drawGuide(c, g, gx, gy, dx, dy, power)
            val nx = (if (drawing) a!!.pullX else gx) - dx * rest
            val ny = (if (drawing) a!!.pullY else gy) - dy * rest
            painter.bow(c, gx, gy, dx, dy, bowSize, drawing, nx, ny, power)
            if (!drawing && g.phase == Phase.AIM) drawPinchDot(c, nx, ny, now)
        }
        c.restore()
        if (drawing) drawMeter(c, power, now)
    }

    /** Faint rail the bow slides along. */
    private fun drawAxis(c: Canvas, y: Float) {
        val w = width.toFloat()
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = Color.argb(45, 255, 255, 255)
        val x0 = w * AimTracker.RAIL_MIN
        val x1 = w * AimTracker.RAIL_MAX
        c.drawLine(x0, y, x1, y, strokePaint)
        fillPaint.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(x0, y, dp(3f), fillPaint)
        c.drawCircle(x1, y, dp(3f), fillPaint)
    }

    /** Pulsing red dot on the string: pinch here to nock an arrow. */
    private fun drawPinchDot(c: Canvas, x: Float, y: Float, now: Long) {
        val p = (now % 1100) / 1100f
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = PINCH_RED
        strokePaint.alpha = ((1f - p) * 200).toInt()
        c.drawCircle(x, y, dp(14f) + dp(22f) * easeOutCubic(p), strokePaint)
        fillPaint.color = Color.WHITE
        c.drawCircle(x, y, dp(13f), fillPaint)
        fillPaint.color = PINCH_RED
        c.drawCircle(x, y, dp(10.5f), fillPaint)
        // two little fingertips closing in: the pinch sign
        val gap = dp(4f) + dp(3f) * (1f - easeOutCubic(min(1f, p * 2f)))
        fillPaint.color = Color.WHITE
        c.drawCircle(x - gap, y, dp(2.6f), fillPaint)
        c.drawCircle(x + gap, y, dp(2.6f), fillPaint)
        text(c, "PINCH", x, y + dp(28f), sp(11f), PINCH_RED)
    }

    /** Short dotted aim line: shows direction, not where the shot lands. */
    private fun drawGuide(c: Canvas, g: BullseyeGame, x0: Float, y0: Float, dx: Float, dy: Float, p: Float) {
        if (dy > -0.2f) return
        val color = zoneColor(p)
        val stopY = g.targetY + g.radius * 1.2f
        for (i in 1..8) {
            val x = x0 + dx * dp(26f) * i
            val y = y0 + dy * dp(26f) * i
            if (y < stopY) break
            fillPaint.color = color
            fillPaint.alpha = 230 - i * 24
            c.drawCircle(x, y, dp(4f) - i * dp(0.25f), fillPaint)
        }
    }

    private fun drawMeter(c: Canvas, p: Float, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = w - dp(36f)
        val x1 = x0 + dp(16f)
        val top = h * 0.40f
        val bottom = h * 0.70f
        val span = bottom - top
        val max = AimTracker.MAX_POWER
        fun yOf(v: Float) = bottom - span * (v / max)

        rect.set(x0 - dp(3f), top - dp(3f), x1 + dp(3f), bottom + dp(3f))
        fillPaint.color = Color.argb(170, 10, 10, 18)
        c.drawRoundRect(rect, dp(11f), dp(11f), fillPaint)
        // zones
        fillPaint.color = Color.argb(70, 105, 240, 174)
        c.drawRect(x0, yOf(BullseyeGame.SWEET_HI), x1, yOf(BullseyeGame.SWEET_LO), fillPaint)
        fillPaint.color = Color.argb(70, 255, 82, 82)
        c.drawRect(x0, top, x1, yOf(1f), fillPaint)
        // fill
        val over = p > 1f
        fillPaint.color = zoneColor(p)
        if (over && (now / 120) % 2 == 0L) fillPaint.color = Color.WHITE
        rect.set(x0, yOf(min(p, max)), x1, bottom)
        c.drawRoundRect(rect, dp(8f), dp(8f), fillPaint)
        // 100% line
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = Color.WHITE
        c.drawLine(x0 - dp(5f), yOf(1f), x1 + dp(5f), yOf(1f), strokePaint)

        val pct = (p * 100).toInt().coerceIn(0, PERCENT.size - 1)
        text(c, PERCENT[pct], (x0 + x1) / 2f, top - dp(18f), sp(14f), zoneColor(p))
        text(c, "POWER", (x0 + x1) / 2f, bottom + dp(16f), sp(9f), MUTED, outline = false)
    }

    private fun zoneColor(p: Float): Int = when {
        p > 1f -> LOSE_RED
        p > BullseyeGame.SWEET_HI -> GOLD
        p >= BullseyeGame.SWEET_LO -> WIN_GREEN
        else -> YOU_COLOR
    }

    // ---------- HUD ----------

    private fun drawHud(c: Canvas, g: BullseyeGame, now: Long, w: Float) {
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
        if (bestFor != g.best) {
            bestFor = g.best
            bestStr = "BEST ${g.best}"
        }

        text(c, "STAGE", w * 0.17f, top + dp(22f), sp(11f), MUTED, outline = false)
        text(c, STAGE_NUM[g.stageIndex], w * 0.17f, top + dp(50f), sp(24f), Color.WHITE, outline = false)
        text(c, "SCORE", w * 0.83f, top + dp(22f), sp(11f), MUTED, outline = false)
        text(c, scoreStr, w * 0.83f, top + dp(50f), sp(28f), GOLD, outline = false)

        text(c, g.stage.title, w / 2f, top + dp(20f), sp(12f), Color.WHITE, outline = false)
        val spacing = dp(22f)
        val startX = w / 2f - spacing * 2f
        val py = top + dp(44f)
        val current = g.phase == Phase.AIM || g.phase == Phase.FLIGHT
        for (i in 0 until BullseyeGame.SHOTS) {
            val x = startX + i * spacing
            val pts = g.shotPoints[i]
            if (pts >= 0) {
                fillPaint.color = pipColor(pts)
                c.drawCircle(x, py, dp(7f), fillPaint)
                if (pts == 0) {
                    strokePaint.strokeWidth = dp(2f)
                    strokePaint.color = LOSE_RED
                    c.drawLine(x - dp(3.5f), py - dp(3.5f), x + dp(3.5f), py + dp(3.5f), strokePaint)
                    c.drawLine(x - dp(3.5f), py + dp(3.5f), x + dp(3.5f), py - dp(3.5f), strokePaint)
                }
            } else {
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.argb(90, 255, 255, 255)
                c.drawCircle(x, py, dp(6f), strokePaint)
            }
            if (current && i == g.shotIndex) {
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = GOLD
                c.drawCircle(x, py, dp(9f) + sin(now / 150f) * dp(1.5f), strokePaint)
            }
        }
        text(c, bestStr, w / 2f, top + dp(66f), sp(10f), MUTED, outline = false)

        if (g.stage.windy && g.phase != Phase.GAME_OVER) drawWindChip(c, g, w)
    }

    private fun drawWindChip(c: Canvas, g: BullseyeGame, w: Float) {
        val cy = barBottom + dp(24f)
        rect.set(w / 2f - dp(70f), cy - dp(15f), w / 2f + dp(70f), cy + dp(15f))
        fillPaint.color = Color.argb(180, 0, 0, 0)
        c.drawRoundRect(rect, dp(15f), dp(15f), fillPaint)
        text(c, "WIND", w / 2f - dp(38f), cy, sp(12f), Color.WHITE, outline = false)
        // arrow: length shows strength
        val dir = if (g.wind >= 0f) 1f else -1f
        val len = dp(14f) + dp(36f) * abs(g.wind)
        val x0 = w / 2f + dp(30f) - dir * len / 2f
        val x1 = w / 2f + dp(30f) + dir * len / 2f
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = FLAG
        c.drawLine(x0, cy, x1, cy, strokePaint)
        c.drawLine(x1, cy, x1 - dir * dp(7f), cy - dp(6f), strokePaint)
        c.drawLine(x1, cy, x1 - dir * dp(7f), cy + dp(6f), strokePaint)
    }

    private fun pipColor(points: Int): Int = when (points) {
        10 -> RING_COLORS[4]
        8 -> RING_COLORS[3]
        6 -> RING_COLORS[2]
        4 -> Color.parseColor("#78909C")
        2 -> RING_COLORS[0]
        else -> Color.argb(120, 60, 20, 20)
    }

    // ---------- phase overlays ----------

    private fun drawIntro(c: Canvas, g: BullseyeGame, t: Long, w: Float, h: Float) {
        val cy = h * 0.56f
        val fadeOut = if (t > BullseyeGame.INTRO_MS - 300) (BullseyeGame.INTRO_MS - t) / 300f else 1f
        val alpha = (255 * fadeOut.coerceIn(0f, 1f)).toInt()
        fillPaint.color = Color.BLACK
        fillPaint.alpha = alpha * 120 / 255
        c.drawRect(0f, cy - dp(78f), w, cy + dp(66f), fillPaint)
        val p = (t / 450f).coerceAtMost(1f)
        text(c, stageStr, w / 2f, cy - dp(36f), sp(40f), GOLD, alpha, 0.6f + 0.4f * easeOutBack(p))
        val p2 = ((t - 200) / 400f).coerceIn(0f, 1f)
        text(c, g.stage.title, w / 2f, cy + dp(8f), sp(22f), Color.WHITE, (alpha * p2).toInt())
        text(c, g.stage.tip, w / 2f, cy + dp(40f), sp(14f), SOFT_WHITE, (alpha * p2).toInt())
    }

    private fun drawResult(c: Canvas, g: BullseyeGame, t: Long, tx: Float) {
        val r = g.radius
        if (g.outcome == Outcome.HIT && t < 450) {
            // ring flash at the hit
            val p = t / 450f
            strokePaint.strokeWidth = dp(5f) * (1f - p)
            strokePaint.color = if (g.points == 10) GOLD else Color.WHITE
            strokePaint.alpha = ((1f - p) * 220).toInt()
            c.drawCircle(tx + g.hitOx * r, g.targetY + g.hitOy * r, r * (0.15f + 0.9f * easeOutCubic(p)), strokePaint)
        }

        val (big, small, color) = when (g.outcome) {
            Outcome.HIT -> Triple(POINTS[g.points / 2], LABELS[g.points / 2], pipColor(g.points).let { if (g.points == 2 || g.points == 4) Color.WHITE else it })
            Outcome.MISS -> Triple("MISS", "", LOSE_RED)
            Outcome.SHORT -> Triple("TOO WEAK", "Pull back further", LOSE_RED)
            Outcome.WILD -> Triple("WILD SHOT!", "Aim up at the target", LOSE_RED)
        }
        val p = (t / 380f).coerceAtMost(1f)
        val lift = easeOutCubic((t / 1000f).coerceAtMost(1f)) * dp(16f)
        val alpha = if (t > BullseyeGame.RESULT_MS - 250) ((BullseyeGame.RESULT_MS - t) / 250f * 255).toInt() else 255
        val y = g.targetY - r * 1.25f - dp(26f) - lift
        text(c, big, tx.coerceIn(dp(90f), width - dp(90f)), y, sp(if (g.points == 10) 40f else 32f), color, alpha, 0.5f + 0.5f * easeOutBack(p))
        val sub = if (g.shotShake > 0.45f && g.points < 8) "Too much power, it shook!" else small
        if (sub.isNotEmpty()) text(c, sub, width / 2f, y + dp(32f), sp(14f), if (sub === small) Color.WHITE else LOSE_RED, alpha)
        if (streakStr.isNotEmpty() && g.outcome == Outcome.HIT) {
            text(c, streakStr, width / 2f, y + dp(56f), sp(13f), GOLD, alpha)
        }
    }

    private fun drawStageClear(c: Canvas, t: Long, w: Float, h: Float) {
        val p = (t / 350f).coerceAtMost(1f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (p * 150).toInt()
        c.drawRect(0f, 0f, w, h, fillPaint)
        val cy = h * 0.48f
        text(c, stageClearStr, w / 2f, cy - dp(30f), sp(34f), GOLD, 255, easeOutBack(p))
        val p2 = ((t - 250) / 400f).coerceIn(0f, 1f)
        text(c, stagePtsStr, w / 2f, cy + dp(14f), sp(20f), Color.WHITE, (p2 * 255).toInt())
        text(c, nextStageStr, w / 2f, cy + dp(46f), sp(14f), SOFT_WHITE, (p2 * 255).toInt())
    }

    private fun drawGameOver(c: Canvas, g: BullseyeGame, now: Long, t: Long, w: Float, h: Float) {
        val p = (t / 400f).coerceAtMost(1f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (p * 175).toInt()
        c.drawRect(0f, 0f, w, h, fillPaint)
        val cy = h * 0.42f
        text(c, "RANGE COMPLETE", w / 2f, cy - dp(120f), sp(16f), MUTED, (p * 255).toInt())
        val sp1 = ((t - 150) / 450f).coerceIn(0f, 1f)
        if (sp1 > 0f) text(c, scoreStr, w / 2f, cy - dp(58f), sp(76f), GOLD, 255, easeOutBack(sp1))
        text(c, "of ${BullseyeGame.MAX_SCORE}", w / 2f, cy - dp(8f), sp(14f), SOFT_WHITE, (sp1 * 255).toInt())

        val sr = dp(22f)
        for (i in 0 until 3) {
            val sp2 = ((t - 500 - i * 220) / 350f).coerceIn(0f, 1f)
            if (sp2 <= 0f) continue
            val x = w / 2f + (i - 1) * dp(62f)
            val y = cy + dp(42f) - if (i == 1) dp(8f) else 0f
            val s = sr * easeOutBack(sp2)
            c.save()
            c.translate(x, y)
            c.scale(s, s)
            fillPaint.color = if (i < g.stars) GOLD else Color.argb(70, 255, 255, 255)
            c.drawPath(starPath, fillPaint)
            c.restore()
        }

        val p3 = ((t - 1200) / 400f).coerceIn(0f, 1f)
        if (p3 > 0f) {
            if (g.newBest) {
                text(c, "NEW BEST!", w / 2f, cy + dp(100f), sp(22f), GOLD, (p3 * 255).toInt(), 1f + 0.06f * sin(now / 140f))
            } else {
                text(c, bestStr, w / 2f, cy + dp(100f), sp(16f), SOFT_WHITE, (p3 * 255).toInt())
            }
        }
    }

    private fun drawHint(c: Canvas, g: BullseyeGame, a: AimState?, w: Float, h: Float, now: Long) {
        val drawing = a?.drawing == true
        val p = a?.power ?: 0f
        val (msg, color) = when {
            drawing && p > 1f -> "Too much power! Ease off" to LOSE_RED
            drawing && g.shake > 0.25f -> "Your arm is shaking, shoot now!" to GOLD
            drawing && p >= BullseyeGame.SWEET_LO -> (if (p <= BullseyeGame.SWEET_HI) "Perfect power, let go!" else "Almost too much…") to
                (if (p <= BullseyeGame.SWEET_HI) WIN_GREEN else GOLD)
            drawing -> "Pull back further…" to Color.WHITE
            g.weapon == Weapon.SLINGSHOT ->
                (if (a?.hands.isNullOrEmpty()) "Show one hand to the camera ✋" else "Pinch thumb + index, then pull down") to SOFT_WHITE
            (a?.hands?.size ?: 0) < 2 -> "Show both hands: one steers, one pulls" to SOFT_WHITE
            else -> "Slide & tilt ✌ to aim, pinch the red dot, pull down" to SOFT_WHITE
        }
        val border = if (color == SOFT_WHITE || color == Color.WHITE) Color.argb(80, 255, 255, 255) else color
        val pulse = if (drawing && p > 1f) 1f + 0.05f * sin(now / 60f) else 1f
        c.save()
        c.scale(pulse, pulse, w / 2f, h - dp(40f))
        drawPill(c, msg, w / 2f, h - dp(40f), sp(15f), color, Color.argb(185, 0, 0, 0), border, 255)
        c.restore()
    }

    // ---------- shared widgets ----------

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

    // ---------- particles ----------

    private fun spawnSparks(x: Float, y: Float, count: Int, gold: Boolean) {
        for (i in 0 until min(count, sparkN)) {
            val ang = Random.nextFloat() * 2f * PI.toFloat()
            val speed = dp(80f) + Random.nextFloat() * dp(if (gold) 260f else 140f)
            sX[i] = x
            sY[i] = y
            sVx[i] = cos(ang) * speed
            sVy[i] = sin(ang) * speed - dp(60f)
            sLife[i] = 0.5f + Random.nextFloat() * 0.4f
            sColor[i] = if (gold) CONFETTI_COLORS[Random.nextInt(2)] else if (i % 2 == 0) STRAW else Color.WHITE
        }
        for (i in min(count, sparkN) until sparkN) sLife[i] = 0f
        sparksActive = true
    }

    private fun drawSparks(c: Canvas, dt: Float) {
        var alive = false
        for (i in 0 until sparkN) {
            if (sLife[i] <= 0f) continue
            sLife[i] -= dt
            sVy[i] += dp(500f) * dt
            sX[i] += sVx[i] * dt
            sY[i] += sVy[i] * dt
            if (sLife[i] <= 0f) continue
            alive = true
            fillPaint.color = sColor[i]
            fillPaint.alpha = (255 * min(1f, sLife[i] / 0.3f)).toInt()
            c.drawCircle(sX[i], sY[i], dp(2.5f), fillPaint)
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

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

    private companion object {
        val PERCENT = Array(131) { "$it%" }
        val STAGE_NUM = arrayOf("1/3", "2/3", "3/3")
        val POINTS = arrayOf("", "+2", "+4", "+6", "+8", "+10")
        val LABELS = arrayOf("", "EDGE", "OK", "GOOD", "GREAT!", "BULLSEYE!")
        val GOLD = Color.parseColor("#FFD54F")
        val YOU_COLOR = Color.parseColor("#4FC3F7")
        val WIN_GREEN = Color.parseColor("#69F0AE")
        val LOSE_RED = Color.parseColor("#FF5252")
        val MUTED = Color.parseColor("#B0B3C6")
        val SOFT_WHITE = Color.parseColor("#E6FFFFFF")
        val STRAW = Color.parseColor("#D9B97A")
        val STRAW_DARK = Color.parseColor("#8A6A35")
        val LEG = Color.parseColor("#8D5A2B")
        val LEG_DARK = Color.parseColor("#5D3A1A")
        val FLAG = Color.parseColor("#FF7043")
        val PINCH_RED = Color.parseColor("#FF1744")
        const val NOCK_REST = 0.28f
        /** Outer to inner: white, black, blue, red, gold. */
        val RING_COLORS = intArrayOf(
            Color.parseColor("#F4F1EA"), Color.parseColor("#2B2B30"), Color.parseColor("#2FA4E7"),
            Color.parseColor("#E5484D"), Color.parseColor("#FFC83D")
        )
        val CONFETTI_COLORS = intArrayOf(
            Color.parseColor("#FFD54F"), Color.parseColor("#FFF59D"), Color.parseColor("#4FC3F7"),
            Color.parseColor("#FF8A80"), Color.parseColor("#69F0AE"), Color.WHITE
        )
    }
}

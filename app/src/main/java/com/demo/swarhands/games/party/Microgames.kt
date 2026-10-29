package com.demo.swarhands.games.party

import android.graphics.Canvas
import android.graphics.Color
import com.demo.swarhands.games.signs.Sign
import com.demo.swarhands.games.party.Kit.Companion.BLUE
import com.demo.swarhands.games.party.Kit.Companion.GOLD
import com.demo.swarhands.games.party.Kit.Companion.GREEN
import com.demo.swarhands.games.party.Kit.Companion.PARTY
import com.demo.swarhands.games.party.Kit.Companion.PINK
import com.demo.swarhands.games.party.Kit.Companion.RED
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * One 5-second game. The director calls [start] when its instruction appears (the scene is
 * drawn but not judged yet), then [tick] every frame while it's being played, and [draw]
 * every frame. Times are in milliseconds and scale with [speed], so later rounds are quicker.
 */
abstract class Microgame(protected val kit: Kit) {
    enum class Result { WIN, LOSE }

    abstract val title: Words
    protected abstract val baseMs: Long
    protected open val minMs = 2200L
    /** Survival games (dodge, freeze) are won by lasting until the timer runs out. */
    open val winOnTimeout = false
    /** Position games that still make sense with left and right swapped (Boss Mode's 🪞 rounds). */
    open val mirrorable = false
    /** "DON'T …!" instructions: shown in red. */
    open val fakeOut = false
    /** A boss's long special attack. */
    open val bossAttack = false

    var durationMs = 0L; private set
    /** A short reason shown with the result, e.g. "Splat!". */
    var note: Words? = null; protected set

    protected var speed = 1f
    protected var w = 0f
    protected var h = 0f
    /** Milliseconds since play started. */
    protected var playMs = 0f

    fun start(speed: Float) {
        this.speed = speed
        w = kit.w
        h = kit.h
        durationMs = max(minMs, (baseMs / speed).toLong())
        playMs = 0f
        note = null
        setup()
    }

    fun tick(dtMs: Float, f: HandFrame?): Result? {
        playMs += dtMs
        return step(dtMs / 1000f, f)
    }

    protected abstract fun setup()
    protected abstract fun step(dt: Float, f: HandFrame?): Result?
    abstract fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?)

    /** True once [cond] has held without a break for [ms]. */
    protected class Hold {
        private var since = -1f
        fun update(cond: Boolean, at: Float, ms: Float): Boolean {
            if (!cond) {
                since = -1f
                return false
            }
            if (since < 0f) since = at
            return at - since >= ms
        }
        fun reset() {
            since = -1f
        }
    }

    protected fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    protected fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)

    /** The player's palm as a soft ring. */
    protected fun palmMarker(c: Canvas, f: HandFrame?, color: Int, r: Float) {
        if (f == null || !f.visible) return
        kit.fill.color = color
        kit.fill.alpha = 70
        c.drawCircle(f.palmX, f.palmY, r, kit.fill)
        kit.stroke.strokeWidth = kit.dp(3f)
        kit.stroke.color = color
        c.drawCircle(f.palmX, f.palmY, r, kit.stroke)
    }
}

// ============================================================================ 1. FIST

class FistGame(kit: Kit) : Microgame(kit) {
    override val title = Words("FIST!", "MUTTHI BANDH!")
    override val baseMs = 3500L
    private val hold = Hold()
    private var squeeze = 0f

    override fun setup() {
        hold.reset()
        squeeze = 0f
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val fist = f?.sign == Sign.FIST
        squeeze += ((if (fist) 1f else 0f) - squeeze) * min(1f, dt * 12f)
        return if (hold.update(fist, playMs, 250f)) Result.WIN else null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val cx = w / 2f
        val cy = h * 0.5f
        val r = w * 0.2f
        kit.rect.set(cx - r * (1f + 0.35f * squeeze), cy - r * (1f - 0.4f * squeeze), cx + r * (1f + 0.35f * squeeze), cy + r)
        kit.fill.color = PINK
        c.drawOval(kit.rect, kit.fill)
        kit.fill.color = Color.argb(90, 255, 255, 255)
        c.drawCircle(cx - r * 0.4f, cy - r * 0.4f * (1f - 0.4f * squeeze), r * 0.25f, kit.fill)
        kit.emoji(c, if (squeeze > 0.5f) "😵" else "🙂", cx, cy + r * 0.2f * squeeze, r * 0.9f)
        kit.text(c, "STRESS BALL", cx, cy + r * 1.45f, kit.sp(14f), Color.WHITE)
    }
}

// ============================================================================ 2. HIGH FIVE

class HighFiveGame(kit: Kit) : Microgame(kit) {
    override val title = Words("HIGH FIVE!", "TAALI DO!")
    override val mirrorable = true
    override val baseMs = 4500L
    private val hold = Hold()
    private var tx = 0f
    private var ty = 0f
    private var hit = false

    override fun setup() {
        hold.reset()
        hit = false
        tx = rnd(0.25f, 0.75f) * w
        ty = rnd(0.32f, 0.66f) * h
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val on = f != null && f.visible && f.sign == Sign.FIVE && dist(f.palmX, f.palmY, tx, ty) < w * 0.16f
        if (hold.update(on, playMs, 120f)) {
            hit = true
            kit.burst(tx, ty, 24, PARTY)
            return Result.WIN
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val pulse = 1f + 0.06f * sin(now / 130f)
        kit.fill.color = GOLD
        kit.fill.alpha = 60
        c.drawCircle(tx, ty, w * 0.16f * pulse, kit.fill)
        kit.stroke.strokeWidth = kit.dp(4f)
        kit.stroke.color = GOLD
        c.drawCircle(tx, ty, w * 0.16f * pulse, kit.stroke)
        kit.painter.icon(c, Sign.FIVE, tx, ty, w * 0.22f, if (hit) 120 else 255)
        palmMarker(c, f, BLUE, w * 0.07f)
    }
}

// ============================================================================ 3. COUNT

class CountGame(kit: Kit) : Microgame(kit) {
    override val title = Words("COUNT!", "GINO!")
    override val baseMs = 5000L
    override val minMs = 2600L
    private val hold = Hold()
    private var n = 3
    private var last = 0

    override fun setup() {
        hold.reset()
        do n = 1 + Random.nextInt(5) while (n == last)
        last = n
    }

    override fun step(dt: Float, f: HandFrame?): Result? =
        if (hold.update(f?.sign == Sign.fingers(n), playMs, 350f)) Result.WIN else null

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val size = w * 0.16f
        val gap = w * 0.02f
        val total = n * size + (n - 1) * gap
        var x = (w - total) / 2f + size / 2f
        val y = h * 0.42f
        for (i in 0 until n) {
            val bob = sin(now / 220f + i) * kit.dp(5f)
            kit.emoji(c, "🍎", x, y + bob, size)
            x += size + gap
        }
        kit.text(c, if (kit.hinglish) "Kitne seb? Ungliyon se dikhao" else "How many? Show it with fingers", w / 2f, y + size, kit.sp(15f), Color.WHITE)
        // what you're showing right now
        val s = f?.sign
        if (s != null && s.number in 1..5 && s == Sign.fingers(s.number)) {
            kit.text(c, SHOWN[s.number], w / 2f, y + size * 1.6f, kit.sp(26f), if (s.number == n) GREEN else Color.WHITE)
        }
    }

    private companion object {
        val SHOWN = Array(6) { "✋ $it" }
    }
}

// ============================================================================ 4. THUMBS

class ThumbsGame(kit: Kit) : Microgame(kit) {
    override val title = Words("THUMBS!", "ANGOOTHA!")
    override val baseMs = 3500L
    private val good = Hold()
    private val bad = Hold()
    private var up = true

    override fun setup() {
        good.reset()
        bad.reset()
        up = Random.nextBoolean()
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val want = if (up) Sign.THUMBS_UP else Sign.THUMBS_DOWN
        val other = if (up) Sign.THUMBS_DOWN else Sign.THUMBS_UP
        if (good.update(f?.sign == want, playMs, 300f)) return Result.WIN
        if (bad.update(f?.sign == other, playMs, 400f)) {
            note = Words("Wrong way!", "Ulta!")
            return Result.LOSE
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val cy = h * 0.45f
        val bob = sin(now / 160f) * kit.dp(10f) * (if (up) -1f else 1f)
        kit.emoji(c, if (up) "⬆️" else "⬇️", w / 2f, cy + bob, w * 0.36f)
        val word = if (up) Words("UP", "UPAR") else Words("DOWN", "NEECHE")
        kit.text(c, word.get(kit.hinglish), w / 2f, cy + w * 0.3f, kit.sp(40f), if (up) GREEN else RED)
    }
}

// ============================================================================ 5. PINCH THE BEE

class BeeGame(kit: Kit) : Microgame(kit) {
    override val title = Words("PINCH THE BEE!", "MAKKHI PAKDO!")
    override val mirrorable = true
    override val baseMs = 5000L
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var gx = 0f
    private var gy = 0f
    private var caught = false

    override fun setup() {
        caught = false
        x = rnd(0.3f, 0.7f) * w
        y = rnd(0.35f, 0.6f) * h
        vx = 0f
        vy = 0f
        newGoal()
    }

    private fun newGoal() {
        gx = rnd(0.15f, 0.85f) * w
        gy = rnd(0.28f, 0.72f) * h
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        // wander towards a goal, with a buzzy wobble
        val top = w * 0.32f * speed
        val dx = gx - x
        val dy = gy - y
        val d = max(1f, hypot(dx, dy))
        vx += (dx / d * top - vx) * min(1f, dt * 3f)
        vy += (dy / d * top - vy) * min(1f, dt * 3f)
        x += vx * dt + sin(playMs / 45f) * kit.dp(0.8f)
        y += vy * dt + sin(playMs / 37f) * kit.dp(0.8f)
        if (d < w * 0.05f) newGoal()
        if (f != null && f.visible && f.pinching && dist(f.pinchX, f.pinchY, x, y) < w * 0.12f) {
            caught = true
            kit.burst(x, y, 20, intArrayOf(GOLD, Color.BLACK, Color.WHITE))
            return Result.WIN
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        if (!caught) kit.emoji(c, "🐝", x, y, w * 0.14f, rotate = sin(now / 60f) * 12f + (if (vx < 0) -10f else 10f))
        // pinch cursor between thumb and index
        if (f != null && f.visible) {
            kit.stroke.strokeWidth = kit.dp(3f)
            kit.stroke.color = if (f.pinching) GOLD else Color.WHITE
            c.drawCircle(f.pinchX, f.pinchY, w * 0.05f, kit.stroke)
            if (f.pinching) {
                kit.fill.color = GOLD
                kit.fill.alpha = 120
                c.drawCircle(f.pinchX, f.pinchY, w * 0.05f, kit.fill)
            }
        }
    }
}

// ============================================================================ 6. CATCH THE EGG

class EggGame(kit: Kit) : Microgame(kit) {
    override val title = Words("CATCH!", "ANDA PAKDO!")
    override val mirrorable = true
    override val baseMs = 3000L
    private var x = 0f
    private var y = 0f
    private var y0 = 0f
    private var ground = 0f
    private var fallMs = 2500f
    private var state = 0 // 0 falling, 1 caught, 2 splat

    override fun setup() {
        state = 0
        x = rnd(0.2f, 0.8f) * w
        y0 = h * 0.2f
        y = y0
        ground = h * 0.86f
        fallMs = durationMs - 300f
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val t = (playMs / fallMs).coerceIn(0f, 1f)
        y = y0 + (ground - y0) * t * t // speeds up as it falls
        if (f != null && f.visible && y > f.palmY - h * 0.07f && y < f.palmY + h * 0.04f && abs(x - f.palmX) < w * 0.13f) {
            state = 1
            kit.burst(x, y, 16, intArrayOf(Color.WHITE, GOLD))
            return Result.WIN
        }
        if (t >= 1f) {
            state = 2
            note = Words("Splat!", "Phoot gaya!")
            return Result.LOSE
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        kit.stroke.strokeWidth = kit.dp(2f)
        kit.stroke.color = Color.argb(120, 255, 255, 255)
        c.drawLine(w * 0.05f, ground + w * 0.05f, w * 0.95f, ground + w * 0.05f, kit.stroke)
        when (state) {
            0 -> kit.emoji(c, "🥚", x, y, w * 0.12f, rotate = sin(now / 90f) * 15f)
            2 -> kit.emoji(c, "🍳", x, ground, w * 0.15f)
        }
        if (f != null && f.visible) kit.emoji(c, "🧺", f.palmX, f.palmY + w * 0.03f, w * 0.17f)
    }
}

// ============================================================================ 7. DODGE

class DodgeGame(kit: Kit) : Microgame(kit) {
    override val title = Words("DODGE!", "BACHO!")
    override val mirrorable = true
    override val baseMs = 4000L
    override val winOnTimeout = true
    private val n = 5
    private val bx = FloatArray(n)
    private val by = FloatArray(n)
    private val bv = FloatArray(n)
    private val on = BooleanArray(n)
    private var nextAt = 0f
    private var missingMs = 0f
    private var hitX = -1f
    private var hitY = -1f

    override fun setup() {
        on.fill(false)
        nextAt = 350f
        missingMs = 0f
        hitX = -1f
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val visible = f != null && f.visible
        missingMs = if (visible) 0f else missingMs + dt * 1000f
        if (missingMs > 900f) {
            note = Words("Keep your hand in view", "Haath dikhao!")
            return Result.LOSE
        }
        if (playMs >= nextAt && playMs < durationMs - 600f) {
            nextAt += 1150f / speed
            for (i in 0 until n) if (!on[i]) {
                on[i] = true
                val left = Random.nextBoolean()
                bx[i] = if (left) -w * 0.1f else w * 1.1f
                by[i] = if (visible) f!!.palmY else rnd(0.3f, 0.7f) * h // aimed at your hand
                bv[i] = (if (left) 1f else -1f) * w * 0.95f * speed
                break
            }
        }
        for (i in 0 until n) {
            if (!on[i]) continue
            bx[i] += bv[i] * dt
            if (bx[i] < -w * 0.2f || bx[i] > w * 1.2f) on[i] = false
            if (visible && dist(bx[i], by[i], f!!.palmX, f.palmY) < w * 0.11f) {
                hitX = bx[i]
                hitY = by[i]
                note = Words("Ouch!", "Lag gaya!")
                return Result.LOSE
            }
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        for (i in 0 until n) if (on[i]) kit.emoji(c, "⚽", bx[i], by[i], w * 0.12f, rotate = bx[i] * 0.8f)
        palmMarker(c, f, if (hitX >= 0f) RED else BLUE, w * 0.06f)
        if (hitX >= 0f) kit.emoji(c, "💥", hitX, hitY, w * 0.18f)
    }
}

// ============================================================================ 8. POP THE BALLOONS

class PopGame(kit: Kit) : Microgame(kit) {
    override val title = Words("POP!", "PHODO!")
    override val mirrorable = true
    override val baseMs = 5500L
    override val minMs = 3000L
    private val n = 3
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val popped = BooleanArray(n)

    override fun setup() {
        popped.fill(false)
        for (i in 0 until n) {
            var tries = 0
            do {
                x[i] = rnd(0.15f, 0.85f) * w
                y[i] = rnd(0.3f, 0.7f) * h
                tries++
            } while (tries < 40 && (0 until i).any { j -> dist(x[i], y[i], x[j], y[j]) < w * 0.3f })
        }
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        if (f == null || !f.visible) return null
        var left = 0
        for (i in 0 until n) {
            if (popped[i]) continue
            if (dist(f.tipX, f.tipY, x[i], y[i]) < w * 0.09f) {
                popped[i] = true
                kit.burst(x[i], y[i], 14, intArrayOf(RED, PINK, Color.WHITE))
            } else {
                left++
            }
        }
        return if (left == 0) Result.WIN else null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        for (i in 0 until n) {
            if (popped[i]) continue
            kit.emoji(c, "🎈", x[i], y[i] + sin(now / 300f + i * 2f) * kit.dp(6f), w * 0.15f)
        }
        if (f != null && f.visible) {
            kit.fill.color = Color.WHITE
            c.drawCircle(f.tipX, f.tipY, kit.dp(9f), kit.fill)
            kit.fill.color = BLUE
            c.drawCircle(f.tipX, f.tipY, kit.dp(5f), kit.fill)
        }
    }
}

// ============================================================================ 9. COPY THE SIGN

class CopyGame(kit: Kit) : Microgame(kit) {
    override val title = Words("COPY!", "NAKAL KARO!")
    override val baseMs = 4500L
    private val hold = Hold()
    private var target = Sign.TWO
    private val pool = listOf(Sign.ONE, Sign.TWO, Sign.THREE, Sign.FIVE, Sign.FIST, Sign.THUMBS_UP, Sign.ROCK, Sign.CALL)

    override fun setup() {
        hold.reset()
        var s: Sign
        do s = pool[Random.nextInt(pool.size)] while (s == target)
        target = s
    }

    override fun step(dt: Float, f: HandFrame?): Result? =
        if (hold.update(f?.sign == target, playMs, 350f)) Result.WIN else null

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val cw = w * 0.52f
        val ch = cw * 1.2f
        val cx = w / 2f
        val cy = h * 0.46f
        kit.rect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
        kit.fill.color = Color.parseColor("#FFF8E7")
        c.drawRoundRect(kit.rect, kit.dp(24f), kit.dp(24f), kit.fill)
        kit.stroke.strokeWidth = kit.dp(4f)
        kit.stroke.color = if (f?.sign == target) GREEN else GOLD
        c.drawRoundRect(kit.rect, kit.dp(24f), kit.dp(24f), kit.stroke)
        kit.painter.icon(c, target, cx, cy - ch * 0.06f, cw * 0.74f)
        kit.text(c, target.title, cx, cy + ch * 0.4f, cw * 0.1f, Color.parseColor("#1A1A1A"), outline = false)
    }
}

// ============================================================================ 10. FREEZE

class FreezeGame(kit: Kit) : Microgame(kit) {
    override val title = Words("FREEZE!", "HILNA MAT!")
    override val baseMs = 3600L
    override val winOnTimeout = true
    private var missingMs = 0f
    private var moved = false
    private var shown = 0f

    override fun setup() {
        missingMs = 0f
        moved = false
        shown = 0f
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val visible = f != null && f.visible
        shown += ((if (visible) f!!.speed else 0f) - shown) * min(1f, dt * 10f)
        missingMs = if (visible) 0f else missingMs + dt * 1000f
        if (missingMs > 900f) {
            note = Words("Keep your hand in view", "Haath dikhao!")
            return Result.LOSE
        }
        if (playMs > GRACE_MS && visible && f!!.speed > MOVE_LIMIT) {
            moved = true
            note = Words("You moved!", "Hil gaye!")
            return Result.LOSE
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val open = if (playing) min(1f, playMs / GRACE_MS) else 0f
        val cy = h * 0.4f
        kit.emoji(c, if (open < 0.5f) "😑" else if (moved) "😠" else "👁️", w / 2f, cy, w * (0.22f + 0.1f * open))
        // how much you're moving
        val bw = w * 0.6f
        val x0 = (w - bw) / 2f
        val y = cy + w * 0.25f
        kit.stroke.strokeWidth = kit.dp(10f)
        kit.stroke.color = Color.argb(80, 255, 255, 255)
        c.drawLine(x0, y, x0 + bw, y, kit.stroke)
        val frac = (shown / MOVE_LIMIT).coerceIn(0f, 1f)
        kit.stroke.color = if (frac < 0.6f) GREEN else if (frac < 0.9f) GOLD else RED
        c.drawLine(x0, y, x0 + bw * max(0.02f, frac), y, kit.stroke)
        palmMarker(c, f, if (moved) RED else GREEN, w * 0.06f)
    }

    private companion object {
        const val GRACE_MS = 600f
        const val MOVE_LIMIT = 0.55f // view widths per second
    }
}

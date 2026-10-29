package com.demo.swarhands.games.party

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import com.demo.swarhands.games.party.Kit.Companion.BLUE
import com.demo.swarhands.games.party.Kit.Companion.GOLD
import com.demo.swarhands.games.party.Kit.Companion.GREEN
import com.demo.swarhands.games.party.Kit.Companion.MUTED
import com.demo.swarhands.games.party.Kit.Companion.RED
import com.demo.swarhands.games.signs.Sign
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ fake-out: DON'T …!

/** A trick instruction: do NOT make the sign. Won by holding out until the timer runs out. */
class DontGame(kit: Kit) : Microgame(kit) {
    private class Trap(val sign: Sign, val words: Words)

    private val traps = listOf(
        Trap(Sign.FIST, Words("DON'T FIST!", "MUTTHI MAT BANDHO!")),
        Trap(Sign.FIVE, Words("DON'T OPEN YOUR HAND!", "HAATH MAT KHOLO!")),
        Trap(Sign.THUMBS_UP, Words("NO THUMBS UP!", "ANGOOTHA MAT DIKHAO!")),
        Trap(Sign.TWO, Words("DON'T SHOW TWO!", "DO MAT DIKHAO!")),
        Trap(Sign.ROCK, Words("NO ROCK ON!", "ROCK MAT KARO!"))
    )
    private var trap = traps[0]
    override val title: Words get() = trap.words
    override val baseMs = 3200L
    override val minMs = 2400L
    override val winOnTimeout = true
    override val fakeOut = true
    private val hold = Hold()
    private var missingMs = 0f

    override fun setup() {
        hold.reset()
        missingMs = 0f
        var t: Trap
        do t = traps[Random.nextInt(traps.size)] while (t === trap)
        trap = t
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val visible = f != null && f.visible
        missingMs = if (visible) 0f else missingMs + dt * 1000f
        if (playMs > 600f && missingMs > 1200f) {
            note = Words("Keep your hand in view", "Haath dikhao!")
            return Result.LOSE
        }
        if (hold.update(f?.sign == trap.sign, playMs, 250f)) {
            note = Words("Gotcha! It said DON'T", "Pakde gaye! Mana kiya tha")
            return Result.LOSE
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val cx = w / 2f
        val cy = h * 0.47f
        val r = w * 0.2f
        kit.painter.icon(c, trap.sign, cx, cy, r * 1.5f, 200)
        kit.stroke.strokeWidth = kit.dp(10f)
        kit.stroke.color = RED
        c.drawCircle(cx, cy, r, kit.stroke)
        c.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, kit.stroke)
        val tease = if (kit.hinglish) "Kar do na… 😏" else "Go on… do it 😏"
        kit.text(c, tease, cx, cy + r * 1.45f, kit.sp(17f), Color.WHITE, (170 + 85 * sin(now / 180f)).toInt())
    }
}

// ============================================================================ Bee King: SWARM

/** Pinch all five bees before time runs out. */
class SwarmGame(kit: Kit) : Microgame(kit) {
    override val title = Words("SWARM! PINCH THEM ALL!", "SAB MAKKHIYAN PAKDO!")
    override val baseMs = 13000L
    override val minMs = 8000L
    override val bossAttack = true
    private val n = 5
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val gx = FloatArray(n)
    private val gy = FloatArray(n)
    private val caught = BooleanArray(n)
    private var left = n

    override fun setup() {
        caught.fill(false)
        left = n
        for (i in 0 until n) {
            x[i] = rnd(0.2f, 0.8f) * w
            y[i] = rnd(0.32f, 0.7f) * h
            vx[i] = 0f
            vy[i] = 0f
            goal(i)
        }
    }

    private fun goal(i: Int) {
        gx[i] = rnd(0.12f, 0.88f) * w
        gy[i] = rnd(0.3f, 0.75f) * h
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        val top = w * 0.26f * speed
        for (i in 0 until n) {
            if (caught[i]) continue
            val dx = gx[i] - x[i]
            val dy = gy[i] - y[i]
            val d = max(1f, hypot(dx, dy))
            vx[i] += (dx / d * top - vx[i]) * min(1f, dt * 3f)
            vy[i] += (dy / d * top - vy[i]) * min(1f, dt * 3f)
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            if (d < w * 0.05f) goal(i)
            if (f != null && f.visible && f.pinching && dist(f.pinchX, f.pinchY, x[i], y[i]) < w * 0.11f) {
                caught[i] = true
                left--
                kit.burst(x[i], y[i], 12, intArrayOf(GOLD, Color.BLACK))
            }
        }
        return if (left == 0) Result.WIN else null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        for (i in 0 until n) {
            if (!caught[i]) kit.emoji(c, "🐝", x[i], y[i], w * 0.11f, rotate = sin(now / 60f + i) * 12f)
        }
        kit.text(c, LEFT[left], w / 2f, h * 0.82f, kit.sp(18f), GOLD)
        if (f != null && f.visible) {
            kit.stroke.strokeWidth = kit.dp(3f)
            kit.stroke.color = if (f.pinching) GOLD else Color.WHITE
            c.drawCircle(f.pinchX, f.pinchY, w * 0.05f, kit.stroke)
        }
    }

    private companion object {
        val LEFT = Array(6) { "🐝 × $it" }
    }
}

// ============================================================================ Gully Bowler: HIT 3

/** Five balls come at you: swing your hand as each one arrives. Hit three to win. */
class BatGame(kit: Kit) : Microgame(kit) {
    override val title = Words("HIT 3 BALLS! SWING!", "3 BALL MAARO!")
    override val baseMs = 10000L
    override val minMs = 7000L
    override val bossAttack = true
    private val balls = 5
    private val need = 3
    private val state = IntArray(balls) // 0 coming, 1 hit, 2 missed
    private val lane = FloatArray(balls)
    private val hitAt = FloatArray(balls)
    private var travel = 1100f
    private var gap = 1600f
    private var hits = 0
    private var misses = 0
    private var armed = true

    override fun setup() {
        state.fill(0)
        for (i in 0 until balls) lane[i] = rnd(-0.18f, 0.18f)
        travel = 1150f / speed
        gap = travel + 450f
        hits = 0
        misses = 0
        armed = true
    }

    private fun startOf(i: Int) = 500f + i * gap

    override fun step(dt: Float, f: HandFrame?): Result? {
        // a swing = the palm suddenly speeding up
        var swing = false
        if (f != null && f.visible) {
            if (armed && f.speed > SWING_ON) {
                swing = true
                armed = false
            } else if (f.speed < SWING_ON * 0.45f) {
                armed = true
            }
        }
        for (i in 0 until balls) {
            if (state[i] != 0) continue
            val p = (playMs - startOf(i)) / travel
            if (p < 0f) break
            if (swing && p in 0.72f..1.12f) {
                state[i] = 1
                hits++
                hitAt[i] = playMs
                swing = false
                kit.burst(w / 2f + lane[i] * w, h * 0.7f, 14, intArrayOf(GOLD, Color.WHITE, RED))
            } else if (p > 1.12f) {
                state[i] = 2
                misses++
            }
        }
        if (hits >= need) return Result.WIN
        if (misses > balls - need) {
            note = Words("Bowled out!", "Bowled ho gaye!")
            return Result.LOSE
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        // pitch, narrowing into the distance
        val farY = h * 0.3f
        val nearY = h * 0.8f
        kit.fill.color = Color.argb(110, 201, 166, 107)
        pitch.reset()
        pitch.moveTo(w * 0.44f, farY)
        pitch.lineTo(w * 0.56f, farY)
        pitch.lineTo(w * 0.72f, nearY)
        pitch.lineTo(w * 0.28f, nearY)
        pitch.close()
        c.drawPath(pitch, kit.fill)
        // the hit zone
        kit.stroke.strokeWidth = kit.dp(3f)
        kit.stroke.color = Color.argb(150, 255, 255, 255)
        c.drawLine(w * 0.3f, h * 0.72f, w * 0.7f, h * 0.72f, kit.stroke)

        for (i in 0 until balls) {
            val p = (playMs - startOf(i)) / travel
            when {
                !playing || p < 0f -> Unit
                state[i] == 1 -> {
                    // flies off into the sky
                    val q = ((playMs - hitAt[i]) / 600f).coerceIn(0f, 1f)
                    if (q < 1f) ball(c, w / 2f + lane[i] * w + q * w * 0.4f, h * 0.7f - q * h * 0.6f, w * 0.06f * (1f - q * 0.6f))
                }
                state[i] == 0 -> ball(c, w / 2f + lane[i] * w * p, farY + (h * 0.72f - farY) * p * p, w * (0.015f + 0.05f * p))
            }
        }
        kit.text(c, HITS[min(hits, need)], w / 2f, h * 0.86f, kit.sp(20f), if (hits > 0) GREEN else Color.WHITE)
        if (f != null && f.visible) kit.emoji(c, "🏏", f.palmX, f.palmY, w * 0.18f, rotate = -30f)
    }

    private fun ball(c: Canvas, x: Float, y: Float, r: Float) {
        kit.fill.color = Color.parseColor("#C62828")
        c.drawCircle(x, y, r, kit.fill)
        kit.fill.color = Color.argb(150, 255, 255, 255)
        c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.28f, kit.fill)
    }

    private val pitch = Path()

    private companion object {
        const val SWING_ON = 1.3f // palm speed, view widths per second
        val HITS = Array(4) { "HITS $it / 3" }
    }
}

// ============================================================================ Sign Sorcerer: REMEMBER

/** Watch four signs, then repeat them in order. */
class MemoryGame(kit: Kit) : Microgame(kit) {
    override val title = Words("REMEMBER & REPEAT!", "YAAD KARO, DOHRAO!")
    override val baseMs = 16000L
    override val minMs = 11000L
    override val bossAttack = true
    private val pool = listOf(Sign.ONE, Sign.TWO, Sign.THREE, Sign.FIVE, Sign.FIST, Sign.THUMBS_UP, Sign.ROCK)
    private val seq = ArrayList<Sign>()
    private var cardMs = 800f
    private var showMs = 0f
    private var step = 0
    private val good = Hold()
    private val bad = Hold()

    override fun setup() {
        seq.clear()
        val n = if (speed > 1.45f) 5 else 4
        while (seq.size < n) {
            val s = pool[Random.nextInt(pool.size)]
            if (s != seq.lastOrNull()) seq += s
        }
        cardMs = max(480f, 850f / speed)
        showMs = seq.size * cardMs
        step = 0
        good.reset()
        bad.reset()
    }

    override fun step(dt: Float, f: HandFrame?): Result? {
        if (playMs < showMs) return null
        val want = seq[step]
        val s = f?.sign
        if (good.update(s == want, playMs, 350f)) {
            step++
            good.reset()
            bad.reset()
            kit.burst(w / 2f, h * 0.45f, 10, intArrayOf(GREEN, Color.WHITE))
            if (step >= seq.size) return Result.WIN
        } else if (bad.update(s != null && s != want && (step == 0 || s != seq[step - 1]), playMs, 650f)) {
            note = Words("Wrong sign!", "Galat!")
            return Result.LOSE
        }
        return null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val cx = w / 2f
        val cy = h * 0.47f
        if (!playing || playMs < showMs) {
            // the sorcerer shows the spell, one sign at a time
            val i = if (playing) (playMs / cardMs).toInt().coerceIn(0, seq.size - 1) else -1
            if (i >= 0) {
                val t = (playMs - i * cardMs) / cardMs
                val a = (255 * min(1f, min(t * 5f, (1f - t) * 5f))).toInt().coerceIn(0, 255)
                card(c, cx, cy, seq[i], a)
                kit.text(c, STEP[i + 1], cx, cy + w * 0.42f, kit.sp(18f), GOLD)
            } else {
                kit.text(c, if (kit.hinglish) "Dhyan se dekho…" else "Watch closely…", cx, cy, kit.sp(22f), Color.WHITE)
            }
            return
        }
        // your turn: slots for each sign
        val size = w * 0.13f
        val gap = w * 0.03f
        val total = seq.size * size + (seq.size - 1) * gap
        var x = (w - total) / 2f
        for (i in seq.indices) {
            kit.rect.set(x, cy - size / 2f, x + size, cy + size / 2f)
            kit.fill.color = when {
                i < step -> GREEN
                i == step -> GOLD
                else -> Color.argb(120, 0, 0, 0)
            }
            c.drawRoundRect(kit.rect, size * 0.25f, size * 0.25f, kit.fill)
            if (i < step) kit.painter.icon(c, seq[i], x + size / 2f, cy, size * 0.8f)
            else kit.text(c, "?", x + size / 2f, cy, size * 0.5f, if (i == step) Color.BLACK else MUTED, outline = false)
            x += size + gap
        }
        kit.text(c, if (kit.hinglish) "Ab tum karo!" else "Now repeat it!", cx, cy + size, kit.sp(18f), Color.WHITE)
    }

    private fun card(c: Canvas, cx: Float, cy: Float, s: Sign, a: Int) {
        val cw = w * 0.46f
        val ch = cw * 1.15f
        kit.rect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
        kit.fill.color = Color.parseColor("#F3E5F5")
        kit.fill.alpha = a
        c.drawRoundRect(kit.rect, kit.dp(22f), kit.dp(22f), kit.fill)
        kit.stroke.strokeWidth = kit.dp(4f)
        kit.stroke.color = Color.parseColor("#AB47BC")
        kit.stroke.alpha = a
        c.drawRoundRect(kit.rect, kit.dp(22f), kit.dp(22f), kit.stroke)
        kit.painter.icon(c, s, cx, cy, cw * 0.78f, a)
    }

    private companion object {
        val STEP = Array(6) { "$it" }
    }
}

// ============================================================================ Kite Demon: FLY THROUGH

/** Steer the kite up and down with your hand through five gaps between the rooftops. */
class KiteGame(kit: Kit) : Microgame(kit) {
    override val title = Words("FLY THROUGH THE GAPS!", "PATANG UDAO!")
    override val baseMs = 11000L
    override val minMs = 8000L
    override val bossAttack = true
    private val n = 5
    private val gx = FloatArray(n)
    private val gap = FloatArray(n)
    private val passed = BooleanArray(n)
    private var spawned = 0
    private var ky = 0f
    private var missingMs = 0f
    private var crashed = false
    private var gapHalf = 0f
    private var wall = 0f

    override fun setup() {
        spawned = 0
        passed.fill(false)
        ky = h * 0.5f
        missingMs = 0f
        crashed = false
        gapHalf = h * (0.15f / min(1.6f, speed))
        wall = w * 0.1f
    }

    private val kx get() = w * 0.28f

    override fun step(dt: Float, f: HandFrame?): Result? {
        val visible = f != null && f.visible
        missingMs = if (visible) 0f else missingMs + dt * 1000f
        if (missingMs > 1100f) {
            note = Words("Keep your hand in view", "Haath dikhao!")
            return Result.LOSE
        }
        if (visible) ky += (f!!.palmY.coerceIn(h * 0.22f, h * 0.85f) - ky) * min(1f, dt * 10f)

        val every = 1500f / speed
        if (spawned < n && playMs > 400f + spawned * every) {
            gx[spawned] = w * 1.1f
            gap[spawned] = rnd(0.32f, 0.74f) * h
            spawned++
        }
        val v = w * 0.42f * speed
        for (i in 0 until spawned) {
            gx[i] -= v * dt
            if (!passed[i] && abs(gx[i] - kx) < wall / 2f + w * 0.04f && abs(ky - gap[i]) > gapHalf - h * 0.03f) {
                crashed = true
                note = Words("Kite torn!", "Patang kat gayi!")
                return Result.LOSE
            }
            if (!passed[i] && gx[i] < kx - wall) {
                passed[i] = true
                kit.burst(kx, ky, 8, intArrayOf(GOLD, Color.WHITE))
            }
        }
        return if (spawned == n && passed[n - 1]) Result.WIN else null
    }

    override fun draw(c: Canvas, now: Long, playing: Boolean, f: HandFrame?) {
        val top = h * 0.2f
        val bottom = h * 0.88f
        for (i in 0 until spawned) {
            val x = gx[i]
            kit.fill.color = Color.parseColor("#455A64")
            c.drawRect(x - wall / 2f, top, x + wall / 2f, gap[i] - gapHalf, kit.fill)
            c.drawRect(x - wall / 2f, gap[i] + gapHalf, x + wall / 2f, bottom, kit.fill)
            kit.fill.color = GOLD
            c.drawRect(x - wall / 2f, gap[i] - gapHalf - kit.dp(4f), x + wall / 2f, gap[i] - gapHalf, kit.fill)
            c.drawRect(x - wall / 2f, gap[i] + gapHalf, x + wall / 2f, gap[i] + gapHalf + kit.dp(4f), kit.fill)
        }
        // the string down to your hand
        kit.stroke.strokeWidth = kit.dp(1.5f)
        kit.stroke.color = Color.argb(180, 255, 255, 255)
        c.drawLine(kx, ky, 0f, bottom, kit.stroke)
        kit.emoji(c, if (crashed) "💥" else "🪁", kx, ky, w * 0.12f, rotate = sin(now / 200f) * 10f)
        var done = 0
        for (i in 0 until n) if (passed[i]) done++
        kit.text(c, GATES[done], w / 2f, h * 0.92f, kit.sp(16f), BLUE)
    }

    private companion object {
        val GATES = Array(6) { "$it / 5" }
    }
}

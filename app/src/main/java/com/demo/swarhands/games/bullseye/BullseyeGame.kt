package com.demo.swarhands.games.bullseye

import com.demo.swarhands.audio.Sfx
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Pure game logic (no drawing). Driven by [update] once per rendered frame on the UI thread.
 *
 * 3 stages x 5 shots: still target -> moving target -> wind. Rings score 10/8/6/4/2.
 *
 * The range is fake 3D: the target stands at depth [DEPTH] (1 = the weapon). A shot's sideways
 * landing point follows the aim line on screen; its height depends only on power, so the
 * green [SWEET_LO]..[SWEET_HI] band on the meter lands near the centre. Pulling past 100%
 * makes the weapon shake, and shake turns into random aim and power error.
 */
class BullseyeGame(val weapon: Weapon, var best: Int, private val listener: Listener) {

    interface Listener {
        fun onSfx(sfx: Sfx, volume: Float = 0.8f, rate: Float = 1f)
        fun onHaptic(strong: Boolean)
        fun onPhaseChanged(phase: Phase)
        fun onLanded()
    }

    enum class Phase { INTRO, AIM, FLIGHT, RESULT, STAGE_CLEAR, GAME_OVER }
    enum class Outcome { HIT, MISS, SHORT, WILD }

    class Stage(val title: String, val tip: String, val moving: Boolean, val windy: Boolean)

    // ---------- layout (view pixels, set by the view) ----------
    var w = 0f; private set
    var h = 0f; private set
    var targetBaseX = 0f; private set
    var targetY = 0f; private set
    var radius = 1f; private set
    var footY = 0f; private set
    var horizonY = 0f; private set
    var slingX = 0f; private set
    var slingY = 0f; private set

    // ---------- state ----------
    var phase = Phase.INTRO; private set
    var phaseStart = 0L; private set
    var stageIndex = 0; private set
    var shotIndex = 0; private set
    var score = 0; private set
    var stageScore = 0; private set
    var streak = 0; private set
    var newBest = false; private set
    var wind = 0f; private set
    var shake = 0f; private set
    val shotPoints = IntArray(SHOTS) { -1 }
    val stage: Stage get() = STAGES[stageIndex]
    private var stageStart = 0L

    // ---------- current shot ----------
    var fromX = 0f; private set
    var fromY = 0f; private set
    var toX = 0f; private set
    var toY = 0f; private set
    var flightDepth = DEPTH; private set
    var flightMs = 600L; private set
    var arc = 0f; private set
    var outcome = Outcome.HIT; private set
    var points = 0; private set
    var shotShake = 0f; private set
    /** Hit point relative to the target centre, in target radii. */
    var hitOx = 0f; private set
    var hitOy = 0f; private set
    /** Missed above or to the side: the projectile keeps flying past the target. */
    var flyOn = false; private set

    // ---------- marks left on the target this stage (in target radii) ----------
    val stuckX = FloatArray(SHOTS)
    val stuckY = FloatArray(SHOTS)
    val stuckTilt = FloatArray(SHOTS)
    var stuckCount = 0; private set

    private var started = false
    private var lastSeq = Int.MIN_VALUE
    private var stretchStep = 0
    private var zoneNotified = false
    private var overNotified = false
    private var overSince = 0L
    private var fullSince = 0L

    fun layout(width: Float, height: Float, dp: Float) {
        w = width
        h = height
        radius = min(w * 0.15f, dp * 78f)
        targetBaseX = w / 2f
        targetY = h * 0.33f
        footY = targetY + radius * 2f
        horizonY = (footY - h / DEPTH) / (1f - 1f / DEPTH)
        slingX = w / 2f
        slingY = h * 0.8f
    }

    /** Screen y of the ground at depth z (1 = bottom of the screen, [DEPTH] = target's feet). */
    fun groundY(z: Float): Float = horizonY + (h - horizonY) / z

    fun targetX(now: Long): Float {
        if (!stage.moving) return targetBaseX
        val t = (now - stageStart) / MOVE_PERIOD_MS.toFloat()
        return targetBaseX + w * 0.24f * sin(2f * PI.toFloat() * t)
    }

    fun restart(now: Long) {
        started = true
        score = 0
        streak = 0
        newBest = false
        startStage(0, now)
    }

    fun update(now: Long, aim: AimState?) {
        if (!started) restart(now)
        if (aim != null && aim.releaseSeq != lastSeq) {
            val fresh = lastSeq != Int.MIN_VALUE
            lastSeq = aim.releaseSeq
            val shot = aim.shot
            if (fresh && phase == Phase.AIM && shot != null) fire(shot, now)
        }
        val t = now - phaseStart
        when (phase) {
            Phase.INTRO -> if (t >= INTRO_MS) enter(Phase.AIM, now)
            Phase.AIM -> updateAim(now, aim)
            Phase.FLIGHT -> if (t >= flightMs) land(now)
            Phase.RESULT -> if (t >= RESULT_MS) nextShot(now)
            Phase.STAGE_CLEAR -> if (t >= STAGE_CLEAR_MS) startStage(stageIndex + 1, now)
            Phase.GAME_OVER -> Unit
        }
        if (phase != Phase.AIM) shake *= 0.85f
    }

    // ---------------------------------------------------------------- aiming

    private fun updateAim(now: Long, aim: AimState?) {
        if (aim == null || !aim.drawing) {
            shake *= 0.85f
            stretchStep = 0
            zoneNotified = false
            overNotified = false
            overSince = 0L
            fullSince = 0L
            return
        }
        val p = aim.power

        // a stretch sound every 10% of pull, rising in pitch
        val step = (min(p, 1.2f) * 10f).toInt()
        if (step > stretchStep) {
            stretchStep = step
            listener.onSfx(if (weapon == Weapon.BOW) Sfx.CREAK else Sfx.STRETCH, 0.55f, 0.75f + min(p, 1.2f) * 0.6f)
        } else if (step < stretchStep - 1) {
            stretchStep = step
        }

        if (p in SWEET_LO..SWEET_HI) {
            if (!zoneNotified) {
                zoneNotified = true
                listener.onHaptic(false)
            }
        } else if (p < SWEET_LO - 0.05f) {
            zoneNotified = false
        }

        var target = 0f
        if (p > 1f) {
            if (overSince == 0L) overSince = now
            if (!overNotified) {
                overNotified = true
                listener.onSfx(Sfx.OVERPOWER, 0.7f)
                listener.onHaptic(true)
            }
            target = 0.55f + (p - 1f) / (AimTracker.MAX_POWER - 1f) * 0.3f + (now - overSince) / 1000f * 0.3f
        } else {
            overSince = 0L
            if (p < 0.97f) overNotified = false
            if (p > 0.95f) target = (p - 0.95f) / 0.05f * 0.25f
        }
        if (weapon == Weapon.BOW) {
            // holding a full draw too long: the arm starts to tremble
            if (p >= 0.85f) {
                if (fullSince == 0L) fullSince = now
                val held = (now - fullSince - FATIGUE_MS) / 1000f
                if (held > 0f) target += min(0.6f, held * 0.35f)
            } else {
                fullSince = 0L
            }
        }
        shake += (target.coerceIn(0f, 1f) - shake) * 0.2f
    }

    // ---------------------------------------------------------------- shooting

    private fun fire(shot: Shot, now: Long) {
        val err = shake
        shotShake = err
        val pEff = (min(shot.power, 1f) + (Random.nextFloat() * 2f - 1f) * err * 0.14f).coerceAtLeast(0f)
        val yawErr = (Random.nextFloat() * 2f - 1f) * err * 0.18f // up to ~10 degrees
        fromX = shot.fromX
        fromY = shot.fromY
        flyOn = false
        listener.onSfx(if (weapon == Weapon.BOW) Sfx.TWANG else Sfx.SNAP, 0.9f)
        listener.onHaptic(false)

        val angle = (atan2(shot.dirX, -shot.dirY) + yawErr).coerceIn(-1.25f, 1.25f)
        if (shot.dirY > -0.2f) {
            // pulled sideways or upward: the shot sails off the range
            outcome = Outcome.WILD
            flightDepth = DEPTH * 0.7f
            toX = fromX + shot.dirX * w
            toY = fromY + shot.dirY * h * 0.3f - h * 0.15f
            flightMs = 450L
            flyOn = true
        } else if (pEff < SHORT_POWER) {
            outcome = Outcome.SHORT
            flightDepth = 1f + (DEPTH - 1f) * (0.25f + 0.6f * pEff / SHORT_POWER)
            toY = groundY(flightDepth)
            toX = fromX + tan(angle) * (fromY - toY)
            flightMs = 380L + (pEff * 300).toLong()
        } else {
            outcome = Outcome.HIT // decided on landing
            flightDepth = DEPTH
            val aimY = targetY + (SWEET - pEff) * VERT_K * radius
            toX = fromX + tan(angle) * (fromY - targetY) + wind * WIND_K * radius
            toY = min(aimY, footY - radius * 0.05f)
            hitOy = (aimY - targetY) / radius
            flightMs = 480L + ((1f - pEff) * 260).toLong()
        }
        arc = h * 0.05f * (1.3f - min(pEff, 1f))
        enter(Phase.FLIGHT, now)
    }

    private fun land(now: Long) {
        points = 0
        when (outcome) {
            Outcome.WILD -> {
                streak = 0
                listener.onSfx(Sfx.MISS, 0.5f)
            }
            Outcome.SHORT -> {
                streak = 0
                listener.onSfx(Sfx.MISS)
            }
            else -> {
                hitOx = (toX - targetX(now)) / radius
                val d = hypot(hitOx, hitOy)
                if (d <= 1f) {
                    outcome = Outcome.HIT
                    val ring = ceil(d / 0.2f).toInt().coerceIn(1, 5)
                    points = 12 - ring * 2
                    if (stuckCount < SHOTS) {
                        stuckX[stuckCount] = hitOx
                        stuckY[stuckCount] = hitOy
                        stuckTilt[stuckCount] = (fromX - toX) / w
                        stuckCount++
                    }
                    listener.onSfx(if (weapon == Weapon.BOW) Sfx.THUNK else Sfx.KNOCK, 0.95f)
                    if (points == 10) {
                        listener.onSfx(Sfx.BULLSEYE)
                        listener.onHaptic(true)
                    }
                    streak = if (points >= 8) streak + 1 else 0
                } else {
                    outcome = Outcome.MISS
                    streak = 0
                    flyOn = hitOy < 0.8f // over or past the side; low misses drop in the grass
                    if (!flyOn) listener.onSfx(Sfx.MISS)
                }
            }
        }
        score += points
        stageScore += points
        shotPoints[shotIndex] = points
        enter(Phase.RESULT, now)
        listener.onLanded()
    }

    private fun nextShot(now: Long) {
        shotIndex++
        when {
            shotIndex < SHOTS -> {
                if (stage.windy) rollWind()
                enter(Phase.AIM, now)
            }
            stageIndex < STAGES.size - 1 -> {
                listener.onSfx(Sfx.ROUND_WIN)
                enter(Phase.STAGE_CLEAR, now)
            }
            else -> {
                newBest = score > best
                if (newBest) best = score
                listener.onSfx(if (newBest || score >= THREE_STARS) Sfx.GAME_WIN else Sfx.ROUND_WIN)
                enter(Phase.GAME_OVER, now)
            }
        }
    }

    private fun startStage(index: Int, now: Long) {
        stageIndex = index
        shotIndex = 0
        stageScore = 0
        shotPoints.fill(-1)
        stuckCount = 0
        stageStart = now
        wind = 0f
        if (stage.windy) rollWind()
        listener.onSfx(Sfx.TICK)
        enter(Phase.INTRO, now)
    }

    private fun rollWind() {
        val s = 0.35f + Random.nextFloat() * 0.65f
        wind = if (Random.nextBoolean()) s else -s
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseStart = now
        listener.onPhaseChanged(p)
    }

    val stars: Int
        get() = when {
            score >= THREE_STARS -> 3
            score >= 80 -> 2
            score >= 40 -> 1
            else -> 0
        }

    companion object {
        const val SHOTS = 5
        const val MAX_SCORE = SHOTS * 10 * 3
        const val SWEET_LO = 0.80f
        const val SWEET_HI = 0.95f
        const val SWEET = (SWEET_LO + SWEET_HI) / 2f
        const val DEPTH = 5f
        const val INTRO_MS = 2000L
        const val RESULT_MS = 1100L
        const val STAGE_CLEAR_MS = 2200L
        private const val SHORT_POWER = 0.5f
        private const val VERT_K = 6.5f       // target radii per unit of power off the sweet spot
        private const val WIND_K = 1.3f       // target radii of drift at full wind
        private const val FATIGUE_MS = 1800L
        private const val MOVE_PERIOD_MS = 3200L
        private const val THREE_STARS = 115

        val STAGES = listOf(
            Stage("STILL TARGET", "Pull into the green zone, then let go", moving = false, windy = false),
            Stage("MOVING TARGET", "Lead the target, it keeps sliding", moving = true, windy = false),
            Stage("WINDY RANGE", "Aim against the wind", moving = false, windy = true)
        )
    }
}

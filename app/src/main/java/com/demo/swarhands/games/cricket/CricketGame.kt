package com.demo.swarhands.games.cricket

import com.demo.swarhands.audio.Sfx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Pure game logic (no drawing). Driven by [update] once per rendered frame on the UI thread.
 *
 * The gully is fake 3D seen from just behind the batsman's stumps. World units: X sideways
 * (+ = right), Y up, Z depth (1 = the batting crease, larger = further away). The view
 * projects with [sx] / [sy]: things shrink as 1/Z towards the horizon.
 *
 * Batting: swing your hand when the ball reaches the crease. Timing picks the direction
 * (early = leg side / left, late = off side / right), swing speed gives power, and an upward
 * swing lofts the ball. Everything runs on a game clock that slows down for six replays.
 */
class CricketGame(val mode: CricketMode, level: Int, var best: Int, private val listener: Listener) {

    interface Listener {
        fun onSfx(sfx: Sfx, volume: Float = 0.8f, rate: Float = 1f)
        fun onHaptic(strong: Boolean)
        fun onPhaseChanged(phase: Phase)
        /** The ball was hit (or missed): [outcome] is decided, the ball is about to fly. */
        fun onResolved()
        /** The ball reached the end of its flight (gone for six, window smashed, caught…). */
        fun onImpact()
    }

    enum class Phase { INTRO, RUNUP, DELIVERY, RESULT, LEVEL_CLEAR, GAME_OVER }

    enum class BallType(
        val label: String,
        val ms: Long,          // release -> crease at pace 1
        val bounceZ: Float,    // where it pitches
        val arriveY: Float,    // height at the crease
        val loop: Float,       // extra flight before the bounce
        val runupMs: Long,
        val runupFrom: Float,  // depth the run-up starts from
        val hint: String       // what the run-up tells you
    ) {
        MEDIUM("Medium pace", 1250, 2.4f, 0.55f, 0.25f, 1400, 7.2f, "Medium pacer running in"),
        FAST("Fast ball", 1000, 2.6f, 0.6f, 0.1f, 1650, 9f, "Long run-up: here comes pace!"),
        SLOW("Slower ball", 1650, 2.3f, 0.5f, 0.9f, 1400, 7.2f, "Medium pacer running in"), // same run-up: a trap
        SPIN("Spin", 1550, 2.0f, 0.45f, 0.9f, 950, 6f, "Short walk: it's the spinner"),
        BOUNCER("Bouncer!", 1100, 3.6f, 1.9f, 0.1f, 1650, 9f, "Long run-up: here comes pace!"),
        YORKER("Yorker!", 1050, 1.05f, 0.06f, 0.15f, 1650, 9f, "Long run-up: here comes pace!")
    }

    enum class Outcome(val out: Boolean) {
        DOT(false), BEATEN(false), LEFT(false), RUNS(false), FOUR(false), SIX(false), GOLDEN(false), WALL(false),
        BOWLED(true), CAUGHT(true), CAUGHT_BEHIND(true), WINDOW_OUT(true)
    }

    class Level(val target: Int, val balls: Int, val wickets: Int, val pace: Float, val types: List<BallType>)

    /** A window on a house wall. [side] = -1 left wall, +1 right wall. */
    class Window(val side: Int, val z0: Float, val z1: Float, val y0: Float, val y1: Float) {
        val zc get() = (z0 + z1) / 2f
        val yc get() = (y0 + y1) / 2f
    }

    class House(val side: Int, val z0: Float, val z1: Float, val height: Float, val color: Int)

    // ---------- layout (view pixels, set by the view) ----------
    var w = 0f; private set
    var h = 0f; private set
    var horizonY = 0f; private set
    private var baseY = 0f
    private var unit = 1f

    fun layout(width: Float, height: Float) {
        w = width
        h = height
        horizonY = h * 0.27f
        baseY = h * 0.86f
        unit = w * 0.2f
    }

    /**
     * Perspective factor: 1 at the crease, shrinking with depth. The camera sits [CAM_BACK]
     * behind the crease (a broadcast-style long lens), so the far end stays readable.
     */
    private fun f(z: Float) = (1f + CAM_BACK) / (max(z, 0.35f) + CAM_BACK)
    /** Screen x of world X at depth z. */
    fun sx(x: Float, z: Float) = w / 2f + x * unit * f(z)
    /** Screen y of height Y at depth z. */
    fun sy(y: Float, z: Float) = horizonY + (baseY - horizonY) * f(z) - y * unit * f(z)
    /** Pixels per world unit at depth z. */
    fun scale(z: Float) = unit * f(z)

    // ---------- match state ----------
    var level = level; private set
    var phase = Phase.INTRO; private set
    var clock = 0.0; private set
    var phaseStart = 0.0; private set
    /** Game time runs this much slower than real time (six replays). */
    var timeScale = 1f; private set
    var runs = 0; private set
    var wickets = 0; private set
    var balls = 0; private set
    var won = false; private set
    var newBest = false; private set
    /** This over, ball by ball: -1 = not bowled, [PIP_OUT] = wicket, else runs. */
    val pips = IntArray(6) { -1 }
    val broken = BooleanArray(WINDOWS.size)
    var golden = -1; private set
    val levelInfo: Level get() = LEVELS[min(level, LEVELS.size) - 1]
    val maxWickets get() = if (mode == CricketMode.CHASE) levelInfo.wickets else SUPER_WICKETS
    val target get() = levelInfo.target
    val ballsLeft get() = levelInfo.balls - balls

    // ---------- current ball ----------
    var type = BallType.MEDIUM; private set
    var kmh = 0; private set
    private var durMs = 1250.0
    private var releaseX = 0.3f
    private var lineX = 0f
    private var turn = 0f
    private var deliveryStart = 0.0
    private var arriveAt = 0.0
    private var bounced = false
    private var swung = false
    private var resolved = false

    var outcome = Outcome.DOT; private set
    var shotRuns = 0; private set
    /** Timing of the contact: <0 early, >0 late, in ms (0 = perfect). */
    var timingMs = 0f; private set
    var contact = false; private set
    var resolvedAt = 0.0; private set
    var impactDone = false; private set
    /** The house window this shot flies at (window outcomes). */
    var targetWindow = -1; private set
    /** Fielder for a catch, world X / Z. */
    var fielderX = 0f; private set
    var fielderZ = 0f; private set

    // ---------- ball (world) ----------
    var ballVisible = false; private set
    var bx = 0f; private set
    var by = 0f; private set
    var bz = 0f; private set

    // ---------- bowler ----------
    var bowlerZ = 9f; private set
    /** 0..1 through the run-up; 1 = the ball is out of the hand. */
    var runupProgress = 0f; private set

    // ---------- flight after the shot ----------
    private var fx0 = 0f
    private var fy0 = 0f
    private var fz0 = 0f
    private var fx1 = 0f
    private var fy1 = 0f
    private var fz1 = 0f
    private var fPeak = 0f
    private var fMs = 1000.0
    private var fRoll = false
    private var resultMs = 1800.0
    private var scored = true

    private var started = false
    private var lastSeq = Int.MIN_VALUE
    private var lastNow = 0L
    private var ease = 1f

    // ---------------------------------------------------------------- public

    fun restart() {
        started = true
        if (mode == CricketMode.CHASE && won && level >= LEVELS.size) level = 1 // champion: start over
        startInnings()
    }

    fun update(now: Long, bat: BatState?) {
        val realDt = if (lastNow == 0L) 16L else (now - lastNow).coerceIn(0L, 50L)
        lastNow = now
        timeScale = if (phase == Phase.RESULT && outcome == Outcome.SIX && clock - phaseStart < SLOWMO_MS) SLOWMO else 1f
        clock += realDt * timeScale
        if (!started) restart()

        if (bat != null && bat.swingSeq != lastSeq) {
            val fresh = lastSeq != Int.MIN_VALUE
            lastSeq = bat.swingSeq
            val s = bat.swing
            if (fresh && s != null) onSwing(s, now)
        }

        val t = clock - phaseStart
        when (phase) {
            Phase.INTRO -> if (t >= INTRO_MS) startBall()
            Phase.RUNUP -> updateRunup(t)
            Phase.DELIVERY -> updateDelivery()
            Phase.RESULT -> updateResult(t)
            Phase.LEVEL_CLEAR -> if (t >= LEVEL_CLEAR_MS) {
                level++
                startInnings()
            }
            Phase.GAME_OVER -> Unit
        }
    }

    // ---------------------------------------------------------------- innings / balls

    private fun startInnings() {
        runs = 0
        wickets = 0
        balls = 0
        won = false
        newBest = false
        pips.fill(-1)
        broken.fill(false)
        pickGolden()
        ease = when {
            mode == CricketMode.SUPER_OVER -> 1.15f
            level == 1 -> 1.25f
            level == 2 -> 1.12f
            else -> 1f
        }
        bowlerZ = 9f
        ballVisible = false
        listener.onSfx(Sfx.TICK)
        enter(Phase.INTRO)
    }

    private fun startBall() {
        if (balls > 0 && balls % 6 == 0) {
            pips.fill(-1)
            pickGolden()
            if (mode == CricketMode.SUPER_OVER) ease = max(1f, ease - 0.05f)
        }
        val types = if (mode == CricketMode.CHASE) levelInfo.types else superTypes()
        type = types[Random.nextInt(types.size)]
        val pace = if (mode == CricketMode.CHASE) levelInfo.pace else max(0.68f, 0.96f.pow(balls / 6))
        durMs = type.ms * pace.toDouble()
        kmh = (140_000 / durMs).roundToInt()
        releaseX = 0.28f
        lineX = if (Random.nextFloat() < 0.6f) (Random.nextFloat() - 0.5f) * 0.2f
        else (if (Random.nextBoolean()) 1f else -1f) * (0.22f + Random.nextFloat() * 0.25f)
        turn = if (type == BallType.SPIN) (if (Random.nextBoolean()) 1f else -1f) * (0.2f + Random.nextFloat() * 0.2f) else 0f
        bounced = false
        swung = false
        resolved = false
        contact = false
        impactDone = false
        targetWindow = -1
        ballVisible = false
        runupProgress = 0f
        bowlerZ = type.runupFrom
        enter(Phase.RUNUP)
    }

    private fun superTypes(): List<BallType> {
        val overs = balls / 6
        return when {
            overs == 0 -> listOf(BallType.MEDIUM, BallType.SLOW)
            overs == 1 -> listOf(BallType.MEDIUM, BallType.SLOW, BallType.FAST, BallType.SPIN)
            overs == 2 -> listOf(BallType.MEDIUM, BallType.SLOW, BallType.FAST, BallType.SPIN, BallType.BOUNCER)
            else -> BallType.entries
        }
    }

    private fun pickGolden() {
        var tries = 0
        do {
            golden = Random.nextInt(WINDOWS.size)
            tries++
        } while ((broken[golden] || WINDOWS[golden].z0 > 9f) && tries < 20)
    }

    private fun updateRunup(t: Double) {
        runupProgress = (t / type.runupMs).toFloat().coerceIn(0f, 1f)
        bowlerZ = type.runupFrom + (RELEASE_Z + 0.2f - type.runupFrom) * runupProgress
        if (t >= type.runupMs) {
            deliveryStart = clock
            arriveAt = clock + durMs
            ballVisible = true
            ballOnDelivery(0f)
            enter(Phase.DELIVERY)
        }
    }

    // ---------------------------------------------------------------- delivery

    private val uBounce get() = ((RELEASE_Z - type.bounceZ) / (RELEASE_Z - CREASE_Z)).coerceIn(0.05f, 1f)
    private val uStumps = (RELEASE_Z - STUMPS_Z) / (RELEASE_Z - CREASE_Z)
    private val onStumps get() = abs(lineX) < STUMP_HALF + BALL_R && type.arriveY < STUMP_H + 0.05f

    /** Ball position at delivery progress u (0 = release, 1 = batting crease). */
    private fun ballOnDelivery(u: Float) {
        bz = RELEASE_Z + (CREASE_Z - RELEASE_Z) * u
        val ub = uBounce
        val bounceX = lineX - turn
        if (u <= ub) {
            val s = u / ub
            bx = releaseX + (bounceX - releaseX) * s
            by = RELEASE_Y * (1f - s) + type.loop * 4f * s * (1f - s)
        } else {
            val v = (u - ub) / max(1f - ub, 0.05f)
            bx = bounceX + turn * v
            by = max(0f, type.arriveY * (1f - (1f - v) * (1f - v)))
        }
    }

    private fun updateDelivery() {
        var u = ((clock - deliveryStart) / durMs).toFloat()
        if (!bounced && u >= uBounce && uBounce < 0.99f) {
            bounced = true
            listener.onSfx(Sfx.BOUNCE, 0.7f)
        }
        if (onStumps) u = min(u, uStumps) // it stops at the stumps
        ballOnDelivery(u)
        if (bz < 0.5f) ballVisible = false
        if (!resolved && clock >= arriveAt + LATE_MS * ease + LATENCY_MS + 40) noContact()
    }

    private fun onSwing(s: Swing, now: Long) {
        listener.onSfx(Sfx.SWISH, 0.35f + 0.45f * s.power, 0.9f + 0.3f * s.power)
        if (phase != Phase.DELIVERY || resolved) return
        swung = true
        // the swing in game-clock time, minus the camera + tracking delay
        val swingClock = s.at - LATENCY_MS - (now - clock)
        val dt = (swingClock - arriveAt).toFloat()
        if (dt >= -EARLY_MS * ease && dt <= LATE_MS * ease) hit(s, dt)
    }

    private fun noContact() {
        resolved = true
        contact = false
        shotRuns = 0
        outcome = when {
            onStumps -> Outcome.BOWLED
            swung -> Outcome.BEATEN
            else -> Outcome.LEFT
        }
        when (outcome) {
            Outcome.BOWLED -> {
                listener.onSfx(Sfx.STUMPS, 1f)
                listener.onSfx(Sfx.GROAN, 0.6f)
                listener.onHaptic(true)
            }
            else -> listener.onSfx(Sfx.MISS, 0.4f)
        }
        resultMs = if (outcome.out) 2300.0 else 1300.0
        finishBall()
    }

    // ---------------------------------------------------------------- shot

    private fun hit(s: Swing, dt: Float) {
        resolved = true
        contact = true
        timingMs = dt
        val win = (if (dt < 0f) EARLY_MS else LATE_MS) * ease
        var q = 1f - (max(0f, abs(dt) - PERFECT_MS) / (win - PERFECT_MS)).coerceIn(0f, 1f)
        var p = s.power
        if (type == BallType.YORKER) q *= 0.8f // dug out at the toes
        val lofted = -s.dirY > LOFT_UP || (type == BallType.BOUNCER && -s.dirY > 0.1f)
        if (type == BallType.BOUNCER && !lofted) p *= 0.7f
        val e = q * (0.35f + 0.65f * p)

        // direction: early -> leg side (left), late -> off side (right), nudged by the swing
        var theta = (dt / win) * 0.95f + s.dirX * 0.25f + (Random.nextFloat() - 0.5f) * 0.16f
        theta = theta.coerceIn(-1.25f, 1.25f)

        fx0 = bx; fy0 = by; fz0 = bz
        fRoll = false
        outcome = when {
            q < 0.22f -> if (Random.nextFloat() < 0.45f) Outcome.CAUGHT_BEHIND else Outcome.RUNS.also { shotRuns = 1 }
            lofted && e >= 0.74f -> Outcome.SIX
            lofted && e >= 0.48f -> if (abs(theta) > 0.5f) windowShot(theta) else
                if (Random.nextFloat() < 0.55f) Outcome.FOUR else Outcome.CAUGHT
            lofted -> Outcome.CAUGHT
            e >= 0.7f -> Outcome.FOUR
            e >= 0.5f -> Outcome.RUNS.also { shotRuns = if (Random.nextFloat() < 0.25f) 3 else 2 }
            e >= 0.28f -> Outcome.RUNS.also { shotRuns = 1 }
            else -> Outcome.DOT
        }
        if (outcome != Outcome.RUNS) shotRuns = RUNS_FOR[outcome.ordinal]
        planFlight(theta, lofted)

        listener.onSfx(Sfx.BAT, 0.6f + 0.4f * e, 0.9f + 0.25f * e)
        listener.onHaptic(e > 0.7f)
        resultMs = when (outcome) {
            Outcome.SIX -> 3400.0
            Outcome.FOUR, Outcome.GOLDEN -> 2300.0
            Outcome.CAUGHT, Outcome.WINDOW_OUT -> 2500.0
            else -> 1700.0
        }
        finishBall()
    }

    /** Lofted towards a house: smash a window (out!), hit the golden one (bonus) or just the wall. */
    private fun windowShot(theta: Float): Outcome {
        val side = if (theta < 0f) -1 else 1
        val meetZ = (CREASE_Z + WALL_X / abs(tan(theta))).coerceIn(1.6f, 8.5f)
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in WINDOWS.indices) {
            val win = WINDOWS[i]
            if (win.side != side || win.z0 > 9f) continue
            val d = abs(win.zc - meetZ) + Random.nextFloat() * 0.6f
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (golden >= 0 && WINDOWS[golden].side == side && Random.nextFloat() < 0.5f) best = golden
        targetWindow = best
        return when {
            best < 0 -> Outcome.WALL
            best == golden -> Outcome.GOLDEN
            Random.nextFloat() < 0.6f -> Outcome.WINDOW_OUT
            else -> Outcome.WALL
        }
    }

    private fun planFlight(theta: Float, lofted: Boolean) {
        val tn = tan(theta)
        when (outcome) {
            Outcome.SIX -> if (abs(theta) < 0.45f) {
                fz1 = 15f; fx1 = (tn * 14f).coerceIn(-2.1f, 2.1f); fy1 = 7.5f; fPeak = 3f; fMs = 2300.0
            } else {
                fx1 = sign(theta) * 3.8f; fz1 = 2f + 4f * cos(theta); fy1 = 7f; fPeak = 2f; fMs = 2100.0
            }
            Outcome.FOUR -> {
                fz1 = 11.5f
                fx1 = tn * (fz1 - CREASE_Z)
                if (abs(fx1) > WALL_X - 0.15f) {
                    fx1 = sign(fx1) * (WALL_X - 0.15f)
                    fz1 = CREASE_Z + abs(fx1 / tn)
                }
                fy1 = 0f
                fPeak = if (lofted) 2.5f else 0f
                fRoll = !lofted
                fMs = 1500.0
            }
            Outcome.RUNS -> {
                fz1 = 2.2f + shotRuns * 1.2f
                fx1 = (tn * (fz1 - CREASE_Z)).coerceIn(-2.1f, 2.1f)
                fy1 = 0f; fPeak = 0f; fRoll = true; fMs = 1100.0
            }
            Outcome.DOT -> {
                fz1 = 1.6f; fx1 = bx + tn * 0.4f; fy1 = 0f; fPeak = 0f; fRoll = true; fMs = 800.0
            }
            Outcome.CAUGHT -> {
                fielderX = (tn * 5f).coerceIn(-1.8f, 1.8f)
                fielderZ = 6.5f
                fx1 = fielderX; fz1 = fielderZ; fy1 = 1.5f; fPeak = 4.5f; fMs = 1500.0
            }
            Outcome.CAUGHT_BEHIND -> {
                fx1 = bx + 0.8f * (if (theta < 0f) -1f else 1f); fz1 = 0.45f; fy1 = 0.9f; fPeak = 0.3f; fMs = 380.0
            }
            Outcome.WINDOW_OUT, Outcome.GOLDEN, Outcome.WALL -> {
                val win = WINDOWS[max(targetWindow, 0)]
                fx1 = win.side * WALL_X
                fz1 = win.zc
                fy1 = if (outcome == Outcome.WALL) win.y0 - 0.35f else win.yc
                fPeak = 1.5f
                fMs = 1200.0
            }
            else -> Unit
        }
    }

    /** Shots that fly somewhere keep their result secret until they land (no spoiling a catch). */
    val revealOnImpact: Boolean
        get() = contact && (outcome == Outcome.CAUGHT || outcome == Outcome.WINDOW_OUT ||
            outcome == Outcome.GOLDEN || outcome == Outcome.WALL || outcome == Outcome.FOUR)

    private fun finishBall() {
        resolvedAt = clock
        scored = false
        if (!revealOnImpact) score()
        listener.onResolved()
        enter(Phase.RESULT)
    }

    private fun score() {
        if (scored) return
        scored = true
        balls++
        runs += shotRuns
        if (outcome.out) wickets++
        pips[(balls - 1) % 6] = if (outcome.out) PIP_OUT else shotRuns
    }

    // ---------------------------------------------------------------- result

    private fun updateResult(t: Double) {
        if (contact) {
            val f = (t / fMs).toFloat()
            if (f < 1f) {
                val g = if (fRoll) 1f - (1f - f) * (1f - f) else f // rolling balls slow down
                bx = fx0 + (fx1 - fx0) * g
                bz = fz0 + (fz1 - fz0) * g
                by = if (fRoll) abs(sin(f * PI.toFloat() * 3f)) * 0.25f * (1f - f)
                else fy0 + (fy1 - fy0) * f + fPeak * 4f * f * (1f - f)
            } else if (!impactDone) {
                impactDone = true
                bx = fx1; by = fy1; bz = fz1
                score()
                impact()
            }
        } else {
            // missed: the ball carries on to the keeper, or rests against the broken stumps
            val u = ((clock - deliveryStart) / durMs).toFloat()
            ballOnDelivery(if (outcome == Outcome.BOWLED) min(u, uStumps) else u)
            if (bz < 0.5f || (outcome == Outcome.BOWLED && t > 700)) ballVisible = false
            if (!impactDone) {
                impactDone = true
                listener.onImpact()
            }
        }
        if (t >= resultMs) afterBall()
    }

    private fun impact() {
        when (outcome) {
            Outcome.SIX -> {
                ballVisible = false
                listener.onSfx(Sfx.CHEER, 1f)
                listener.onSfx(Sfx.GAME_WIN, 0.6f)
            }
            Outcome.FOUR -> {
                listener.onSfx(Sfx.KNOCK, 0.6f, 0.8f)
                listener.onSfx(Sfx.CHEER, 0.8f)
            }
            Outcome.WINDOW_OUT -> {
                if (targetWindow >= 0) broken[targetWindow] = true
                ballVisible = false
                listener.onSfx(Sfx.GLASS, 1f)
                listener.onSfx(Sfx.GROAN, 0.5f)
                listener.onHaptic(true)
            }
            Outcome.GOLDEN -> {
                ballVisible = false
                listener.onSfx(Sfx.GLASS, 0.7f, 1.3f)
                listener.onSfx(Sfx.BULLSEYE, 0.9f)
                listener.onSfx(Sfx.CHEER, 0.9f)
                listener.onHaptic(true)
                golden = -1
            }
            Outcome.WALL -> listener.onSfx(Sfx.KNOCK, 0.8f)
            Outcome.CAUGHT -> {
                listener.onSfx(Sfx.THUNK, 0.6f, 1.4f)
                listener.onSfx(Sfx.GROAN, 0.6f)
                listener.onHaptic(true)
            }
            Outcome.CAUGHT_BEHIND -> {
                ballVisible = false
                listener.onSfx(Sfx.GROAN, 0.6f)
                listener.onHaptic(true)
            }
            else -> Unit
        }
        listener.onImpact()
    }

    private fun afterBall() {
        score()
        ballVisible = false
        if (mode == CricketMode.CHASE) {
            when {
                runs >= target -> {
                    won = true
                    if (level >= LEVELS.size) {
                        newBest = level > best
                        if (newBest) best = level
                        listener.onSfx(Sfx.GAME_WIN)
                        enter(Phase.GAME_OVER)
                    } else {
                        if (level > best) {
                            best = level
                            newBest = true
                        }
                        listener.onSfx(Sfx.ROUND_WIN)
                        enter(Phase.LEVEL_CLEAR)
                    }
                }
                wickets >= maxWickets || ballsLeft <= 0 -> {
                    listener.onSfx(Sfx.GAME_LOSE)
                    enter(Phase.GAME_OVER)
                }
                else -> startBall()
            }
        } else {
            if (wickets >= maxWickets) {
                newBest = runs > best
                if (newBest) best = runs
                listener.onSfx(if (newBest) Sfx.GAME_WIN else Sfx.GAME_LOSE)
                enter(Phase.GAME_OVER)
            } else {
                startBall()
            }
        }
    }

    private fun enter(p: Phase) {
        phase = p
        phaseStart = clock
        listener.onPhaseChanged(p)
    }

    companion object {
        const val RELEASE_Z = 5f
        const val RELEASE_Y = 2.1f
        const val CREASE_Z = 1f
        const val STUMPS_Z = 0.85f
        const val STUMP_HALF = 0.11f
        const val STUMP_H = 0.71f
        const val BALL_R = 0.045f
        const val WALL_X = 2.4f
        const val FAR_Z = 12f
        private const val CAM_BACK = 1.4f
        const val PIP_OUT = 99

        const val INTRO_MS = 2400.0
        const val LEVEL_CLEAR_MS = 2600.0
        const val SLOWMO_MS = 900.0   // game time spent in slow motion (≈2.6 s real)
        const val SLOWMO = 0.35f

        // timing window around the ball reaching the crease
        private const val EARLY_MS = 230f
        private const val LATE_MS = 150f
        private const val PERFECT_MS = 45f
        /** Camera + tracking delay: a swing is reported this long after it happened. */
        private const val LATENCY_MS = 110L
        private const val LOFT_UP = 0.35f

        const val SUPER_WICKETS = 2

        val LEVELS = listOf(
            Level(12, 6, 2, 1f, listOf(BallType.MEDIUM, BallType.SLOW)),
            Level(18, 12, 3, 1f, listOf(BallType.MEDIUM, BallType.SLOW, BallType.SPIN)),
            Level(24, 12, 3, 0.95f, listOf(BallType.MEDIUM, BallType.FAST, BallType.SPIN, BallType.BOUNCER)),
            Level(30, 12, 2, 0.92f, listOf(BallType.FAST, BallType.SLOW, BallType.SPIN, BallType.BOUNCER, BallType.YORKER)),
            Level(36, 12, 2, 0.88f, BallType.entries)
        )

        /** Runs for each [Outcome] (RUNS is decided per shot). */
        private val RUNS_FOR = intArrayOf(0, 0, 0, 0, 4, 6, 10, 2, 0, 0, 0, 0)

        private val HOUSE_COLORS = intArrayOf(
            0xFFE8A87C.toInt(), 0xFF85C7DE.toInt(), 0xFFF6E27F.toInt(), 0xFFC3A6E0.toInt(), 0xFF9ED9A0.toInt(), 0xFFF2A6A6.toInt()
        )

        /** Houses along both walls, near to far. */
        val HOUSES: List<House> = run {
            val l = listOf(1.2f to 3.0f, 3.2f to 5.4f, 5.6f to 8.4f, 8.6f to 12f)
            val r = listOf(1.2f to 2.6f, 2.8f to 4.8f, 5.0f to 7.6f, 7.8f to 12f)
            val heights = floatArrayOf(3.6f, 4.3f, 3.3f, 4.0f)
            val out = ArrayList<House>()
            l.forEachIndexed { i, (a, b) -> out += House(-1, a, b, heights[i], HOUSE_COLORS[i]) }
            r.forEachIndexed { i, (a, b) -> out += House(1, a, b, heights[(i + 2) % 4], HOUSE_COLORS[(i + 3) % 6]) }
            out
        }

        val WINDOWS: List<Window> = run {
            val out = ArrayList<Window>()
            for (hs in HOUSES) {
                val len = hs.z1 - hs.z0
                val cols = if (len > 2.2f) 2 else 1
                val rows = if (hs.height > 3.8f) 2 else 1
                for (c in 0 until cols) {
                    val a = hs.z0 + len * (c + 0.25f) / cols
                    val b = hs.z0 + len * (c + 0.75f) / cols
                    for (r in 0 until rows) {
                        val y0 = 1.1f + r * 1.4f
                        out += Window(hs.side, a, b, y0, y0 + 0.9f)
                    }
                }
            }
            out
        }
    }
}

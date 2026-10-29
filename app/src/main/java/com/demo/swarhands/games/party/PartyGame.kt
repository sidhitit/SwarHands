package com.demo.swarhands.games.party

import com.demo.swarhands.audio.Sfx
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class PartyMode { REGULAR, BOSS }

/** A Boss Mode opponent. [attacks] are its long special games, used when it's badly hurt. */
class Boss(val name: Words, val face: String, val hp: Int, val attacks: List<Microgame>)

/**
 * Runs the party (no drawing): a shouted instruction, a few seconds to do it, ✓ or ✗, next.
 * Driven by [update] once per rendered frame on the UI thread.
 *
 * Regular Fun: endless microgames, faster every [GAMES_PER_LEVEL] games, [LIVES] misses and out.
 * Boss Mode: fight bosses one after another. Each win damages the boss (more with a combo of
 * wins), each miss costs a heart. A hurt boss uses its special attack. Later bosses add
 * fake-outs ("DON'T …!") and mirror rounds. Beat the last boss and they come back stronger.
 */
class PartyGame(
    val mode: PartyMode,
    private val games: List<Microgame>,
    private val tricks: List<Microgame>,
    private val bosses: List<Boss>,
    var best: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onSfx(sfx: Sfx, volume: Float = 0.8f, rate: Float = 1f)
        fun onHaptic(strong: Boolean)
        fun onPhaseChanged(phase: Phase)
    }

    enum class Phase { INTRO, BOSS_INTRO, INSTRUCT, PLAY, RESULT, SPEED_UP, BOSS_DOWN, GAME_OVER }

    var phase = Phase.INTRO; private set
    var phaseStart = 0L; private set
    val maxLives get() = if (mode == PartyMode.BOSS) BOSS_LIVES else LIVES
    var lives = maxLives; private set
    /** Regular: games won. Boss: damage dealt. */
    var score = 0; private set
    var level = 1; private set
    var newBest = false; private set
    var current: Microgame = games[0]; private set
    var lastWon = false; private set
    /** The microgame that took your last life. */
    var lostTo: Microgame? = null; private set

    // ---------- Boss Mode ----------
    var bossIndex = 0; private set
    /** How many times all the bosses have been beaten (each lap they come back stronger). */
    var lap = 0; private set
    var bossHp = 0; private set
    var bossMaxHp = 1; private set
    var combo = 0; private set
    var lastDamage = 0; private set
    var bossesBeaten = 0; private set
    /** This round's controls are left-right swapped. */
    var mirror = false; private set
    /** When the boss last got hit / hit you (for shakes and flashes). */
    var bossHitAt = 0L; private set
    var playerHitAt = 0L; private set
    val boss: Boss get() = bosses[bossIndex]
    val comboMultiplier: Float
        get() = when {
            combo >= 5 -> 3f
            combo >= 3 -> 2f
            combo >= 2 -> 1.5f
            else -> 1f
        }

    private var fightGames = 0
    private var nextRageAt = 0
    private var played = 0
    private val recent = ArrayDeque<Microgame>()
    private var started = false
    private var lastTickSec = -1

    val speed: Float
        get() = if (mode == PartyMode.REGULAR) min(MAX_SPEED, 1f + (level - 1) * SPEED_STEP)
        else min(MAX_SPEED + 0.2f, 1.05f + bossIndex * 0.12f + lap * 0.35f + fightGames * 0.025f)

    fun restart(now: Long) {
        started = true
        lives = maxLives
        score = 0
        level = 1
        played = 0
        newBest = false
        lostTo = null
        combo = 0
        bossesBeaten = 0
        lap = 0
        recent.clear()
        if (mode == PartyMode.BOSS) {
            startBoss(0, now)
        } else {
            enter(Phase.INTRO, now)
        }
    }

    fun update(now: Long, dtMs: Float, f: HandFrame?) {
        if (!started) restart(now)
        val t = now - phaseStart
        when (phase) {
            Phase.INTRO -> if (t >= INTRO_MS) next(now)
            Phase.BOSS_INTRO -> if (t >= BOSS_INTRO_MS) next(now)
            Phase.INSTRUCT -> if (t >= instructMs) {
                lastTickSec = -1
                enter(Phase.PLAY, now)
            }
            Phase.PLAY -> play(now, t, dtMs, input(f))
            Phase.RESULT -> if (t >= RESULT_MS) afterResult(now)
            Phase.SPEED_UP -> if (t >= SPEED_UP_MS) next(now)
            Phase.BOSS_DOWN -> if (t >= BOSS_DOWN_MS) {
                var i = bossIndex + 1
                if (i >= bosses.size) {
                    i = 0
                    lap++
                }
                startBoss(i, now)
            }
            Phase.GAME_OVER -> Unit
        }
    }

    /** What the current game sees: in a mirror round, left and right are swapped. */
    fun input(f: HandFrame?): HandFrame? {
        if (!mirror || f == null || !f.visible) return f
        val w = mirrorWidth
        return HandFrame(
            f.hands, f.partial, f.sign, w - f.palmX, f.palmY, w - f.tipX, f.tipY,
            f.pinching, w - f.pinchX, f.pinchY, f.speed
        )
    }

    /** Screen width for mirroring, set by the view. */
    var mirrorWidth = 0f

    /** How long the instruction shows before play starts (shorter as it speeds up). */
    val instructMs: Long
        get() = if (current.bossAttack) 1800L else max(650L, (1100 / speed).toLong())

    /** 0..1 of the play time left (for the fuse). */
    fun fuse(now: Long): Float =
        if (phase != Phase.PLAY) 1f else (1f - (now - phaseStart) / current.durationMs.toFloat()).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- flow

    private fun startBoss(i: Int, now: Long) {
        bossIndex = i
        bossMaxHp = (bosses[i].hp * (1f + lap * 0.5f)).toInt()
        bossHp = bossMaxHp
        fightGames = 0
        nextRageAt = bossMaxHp / 2
        listener.onSfx(Sfx.OVERPOWER, 0.9f, 0.7f)
        listener.onHaptic(true)
        enter(Phase.BOSS_INTRO, now)
    }

    private fun next(now: Long) {
        mirror = false
        val g = if (mode == PartyMode.BOSS && bossHp <= nextRageAt) {
            // badly hurt: the boss unleashes a special attack
            nextRageAt = if (bossIndex == bosses.size - 1 && nextRageAt > bossMaxHp / 4) bossMaxHp / 6 else -1
            boss.attacks[Random.nextInt(boss.attacks.size)]
        } else {
            pickRegular()
        }
        current = g
        if (mode == PartyMode.BOSS && g.mirrorable && (bossIndex >= 1 || lap > 0) && Random.nextFloat() < MIRROR_CHANCE) {
            mirror = true
        }
        g.start(speed)
        if (g.bossAttack) listener.onSfx(Sfx.OVERPOWER, 1f, 0.8f)
        else listener.onSfx(Sfx.BEAT, 0.8f, min(2f, 0.9f * speed))
        enter(Phase.INSTRUCT, now)
    }

    private fun pickRegular(): Microgame {
        val pool = if (mode == PartyMode.BOSS && (bossIndex >= 1 || lap > 0) && Random.nextFloat() < TRICK_CHANCE) tricks else games
        var g: Microgame
        do g = pool[Random.nextInt(pool.size)] while (g in recent && pool.size > 2)
        recent.addLast(g)
        if (recent.size > 2) recent.removeFirst()
        return g
    }

    private fun play(now: Long, t: Long, dtMs: Float, f: HandFrame?) {
        val left = current.durationMs - t
        val tick = (left / 500).toInt()
        if (left < 1500 && tick != lastTickSec) {
            lastTickSec = tick
            listener.onSfx(Sfx.TICK, 0.5f, 1.2f)
        }
        val r = current.tick(dtMs, f)
        when {
            r == Microgame.Result.WIN -> finish(true, now)
            r == Microgame.Result.LOSE -> finish(false, now)
            t >= current.durationMs -> finish(current.winOnTimeout, now)
        }
    }

    private fun finish(won: Boolean, now: Long) {
        lastWon = won
        played++
        fightGames++
        if (won) {
            if (mode == PartyMode.BOSS) {
                combo++
                val base = if (current.bossAttack) BOSS_ATTACK_DAMAGE else HIT_DAMAGE
                lastDamage = (base * comboMultiplier).toInt()
                bossHp = max(0, bossHp - lastDamage)
                score += lastDamage
                bossHitAt = now
                listener.onSfx(Sfx.THUNK, 1f, 0.9f + 0.1f * min(combo, 5))
            } else {
                score++
            }
            listener.onSfx(Sfx.ROUND_WIN, 0.9f)
            listener.onHaptic(false)
        } else {
            lives--
            combo = 0
            lastDamage = 0
            lostTo = current
            playerHitAt = now
            listener.onSfx(Sfx.ROUND_LOSE, 0.9f)
            listener.onHaptic(true)
        }
        enter(Phase.RESULT, now)
    }

    private fun afterResult(now: Long) {
        when {
            lives <= 0 -> {
                newBest = score > best
                if (newBest) best = score
                listener.onSfx(if (newBest) Sfx.GAME_WIN else Sfx.GAME_LOSE)
                enter(Phase.GAME_OVER, now)
            }
            mode == PartyMode.BOSS && bossHp <= 0 -> {
                bossesBeaten++
                lives = min(maxLives, lives + 1) // a heart back for the win
                listener.onSfx(Sfx.GAME_WIN)
                enter(Phase.BOSS_DOWN, now)
            }
            mode == PartyMode.REGULAR && played % GAMES_PER_LEVEL == 0 && speed < MAX_SPEED -> {
                level++
                listener.onSfx(Sfx.SHOOT, 0.9f, 1.2f)
                enter(Phase.SPEED_UP, now)
            }
            else -> next(now)
        }
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseStart = now
        listener.onPhaseChanged(p)
    }

    companion object {
        const val LIVES = 4
        const val BOSS_LIVES = 5
        const val GAMES_PER_LEVEL = 4
        const val SPEED_STEP = 0.12f
        const val MAX_SPEED = 1.9f
        const val INTRO_MS = 2600L
        const val BOSS_INTRO_MS = 2600L
        const val BOSS_DOWN_MS = 2800L
        const val RESULT_MS = 900L
        const val SPEED_UP_MS = 1400L
        const val HIT_DAMAGE = 10
        const val BOSS_ATTACK_DAMAGE = 30
        private const val TRICK_CHANCE = 0.2f
        private const val MIRROR_CHANCE = 0.3f
    }
}

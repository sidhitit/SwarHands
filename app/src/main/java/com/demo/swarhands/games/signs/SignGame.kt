package com.demo.swarhands.games.signs

import com.demo.swarhands.audio.Sfx
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure game logic (no drawing). Driven by [update] once per rendered frame on the UI thread.
 *
 * Simon-style memory: watch a sequence of hand signs, then repeat it in order. Hold each sign
 * until the ring fills to lock it in (so passing through a pose never counts). Each cleared
 * round adds one sign; later rounds unlock harder signs (two-hand numbers, the heart) and
 * show them faster.
 * A wrong sign or running out of time costs a life and replays the same sequence.
 */
class SignGame(var bestScore: Int, var bestRound: Int, private val listener: Listener) {

    interface Listener {
        fun onSfx(sfx: Sfx, volume: Float = 0.8f, rate: Float = 1f)
        fun onHaptic(strong: Boolean)
        fun onPhaseChanged(phase: Phase)
    }

    enum class Phase { INTRO, SHOW, GO, INPUT, ROUND_CLEAR, MISTAKE, GAME_OVER }

    var phase = Phase.INTRO; private set
    var phaseStart = 0L; private set
    val sequence = ArrayList<Sign>()
    val round get() = sequence.size - 1
    var lives = LIVES; private set
    var score = 0; private set
    var newBest = false; private set
    var lastBonus = 0; private set

    // ---------- show ----------
    var cardMs = 1100L; private set
    /** Index of the card being shown, or -1 in the gap between cards. */
    var showIndex = -1; private set
    var showCardStart = 0L; private set

    // ---------- input ----------
    /** Next sign to repeat (index into [sequence]). */
    var step = 0; private set
    var stepStart = 0L; private set
    /** The sign being held right now and how far its ring has filled (0..1). */
    var holding: Sign? = null; private set
    var holdProgress = 0f; private set
    /** Last sign locked in and when (for the ✓ flash). */
    var lastLocked: Sign? = null; private set
    var lockedAt = 0L; private set
    /** What went wrong: the sign shown by mistake, or null when time ran out. */
    var wrongSign: Sign? = null; private set
    var expected: Sign? = null; private set

    private var holdSince = 0L
    private var needChange = false
    private var lastShown = -1
    private var started = false

    fun restart(now: Long) {
        started = true
        sequence.clear()
        lives = LIVES
        score = 0
        newBest = false
        sequence += pick()
        sequence += pick()
        enter(Phase.INTRO, now)
    }

    fun update(now: Long, state: SignState?) {
        if (!started) restart(now)
        val sign = state?.sign
        val t = now - phaseStart
        when (phase) {
            Phase.INTRO -> if (t >= INTRO_MS) startShow(now)
            Phase.SHOW -> updateShow(now, t)
            Phase.GO -> if (t >= GO_MS) startInput(now)
            Phase.INPUT -> updateInput(now, sign)
            Phase.ROUND_CLEAR -> if (t >= ROUND_CLEAR_MS) {
                sequence += pick()
                startShow(now)
            }
            Phase.MISTAKE -> if (t >= MISTAKE_MS) {
                if (lives <= 0) gameOver(now) else startShow(now)
            }
            Phase.GAME_OVER -> Unit
        }
    }

    /** Milliseconds left to make the current sign. */
    fun timeLeft(now: Long) = max(0L, STEP_MS - (now - stepStart))

    // ---------------------------------------------------------------- show

    private fun startShow(now: Long) {
        cardMs = max(MIN_CARD_MS, BASE_CARD_MS - (round - 1) * 45L)
        showIndex = -1
        lastShown = -1
        enter(Phase.SHOW, now)
    }

    private fun updateShow(now: Long, t: Long) {
        val per = cardMs + GAP_MS
        val i = ((t - LEAD_MS) / per).toInt()
        if (t < LEAD_MS) {
            showIndex = -1
            return
        }
        if (i >= sequence.size) {
            listener.onSfx(Sfx.SHOOT, 0.7f)
            enter(Phase.GO, now)
            return
        }
        val inCard = (t - LEAD_MS) - i * per
        showIndex = if (inCard < cardMs) i else -1
        if (i != lastShown) {
            lastShown = i
            showCardStart = phaseStart + LEAD_MS + i * per
            note(sequence[i], 0.75f)
        }
    }

    // ---------------------------------------------------------------- input

    private fun startInput(now: Long) {
        step = 0
        stepStart = now
        holding = null
        holdProgress = 0f
        needChange = false
        lastLocked = null
        enter(Phase.INPUT, now)
    }

    private fun updateInput(now: Long, sign: Sign?) {
        // the same sign twice in a row needs the hand to change in between
        if (needChange && sign != lastLocked) needChange = false

        if (sign == null || needChange) {
            holding = null
            holdProgress = 0f
        } else if (sign != holding) {
            holding = sign
            holdSince = now
            holdProgress = 0f
        } else {
            holdProgress = ((now - holdSince) / HOLD_MS.toFloat()).coerceAtMost(1f)
            if (holdProgress >= 1f) lock(sign, now)
        }

        if (phase == Phase.INPUT && now - stepStart >= STEP_MS) mistake(null, now)
    }

    private fun lock(sign: Sign, now: Long) {
        holding = null
        holdProgress = 0f
        val want = sequence[step]
        if (sign != want) {
            mistake(sign, now)
            return
        }
        lastLocked = sign
        lockedAt = now
        needChange = true
        score += 10
        note(sign, 0.9f)
        listener.onHaptic(false)
        step++
        stepStart = now
        if (step >= sequence.size) {
            lastBonus = 20 * sequence.size
            score += lastBonus
            listener.onSfx(Sfx.ROUND_WIN)
            enter(Phase.ROUND_CLEAR, now)
        }
    }

    private fun mistake(wrong: Sign?, now: Long) {
        wrongSign = wrong
        expected = sequence[step]
        lives--
        listener.onSfx(Sfx.ROUND_LOSE)
        listener.onHaptic(true)
        enter(Phase.MISTAKE, now)
    }

    private fun gameOver(now: Long) {
        newBest = score > bestScore
        if (newBest) bestScore = score
        if (round > bestRound) bestRound = round
        listener.onSfx(if (newBest) Sfx.GAME_WIN else Sfx.GAME_LOSE)
        enter(Phase.GAME_OVER, now)
    }

    // ---------------------------------------------------------------- helpers

    /** A new sign for the next round: harder signs unlock as rounds go up. */
    private fun pick(): Sign {
        val r = sequence.size - 1
        val maxTier = when {
            r >= 7 -> 3
            r >= 5 -> 2
            r >= 3 -> 1
            else -> 0
        }
        val pool = Sign.entries.filter { it.tier <= maxTier }
        val last = sequence.lastOrNull()
        var s: Sign
        do {
            s = pool[Random.nextInt(pool.size)]
        } while (s == last && r < 5) // no repeats early on
        return s
    }

    /** Every sign has its own sargam note, so the sequence is a little tune you can remember. */
    private fun note(s: Sign, volume: Float) {
        listener.onSfx(Sfx.BEAT, volume, 0.75f * 2f.pow(SEMITONES[s.ordinal % SEMITONES.size] / 12f))
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseStart = now
        listener.onPhaseChanged(p)
    }

    companion object {
        const val LIVES = 3
        const val INTRO_MS = 2600L
        const val GO_MS = 900L
        const val ROUND_CLEAR_MS = 1500L
        const val MISTAKE_MS = 2200L
        const val HOLD_MS = 650L
        const val STEP_MS = 7000L
        private const val LEAD_MS = 400L
        private const val GAP_MS = 320L
        private const val BASE_CARD_MS = 1150L
        private const val MIN_CARD_MS = 650L
        /** Sa Re Ga Ma Pa Dha Ni Sa' Re' Ga' Ma' */
        private val SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17)
    }
}

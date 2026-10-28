package com.demo.swarhands.games.rps

import com.demo.swarhands.audio.Sfx
import kotlin.random.Random

/**
 * Pure game logic (no drawing). Driven by [update] once per rendered frame on the UI thread.
 *
 * Flow: WAIT_START (hold a fist 3 s) -> INTRO (1, 2, 3) -> CHANT ("Stone, Paper, Scissors!")
 *       -> REVEAL -> ... first to 2 wins (best of 3) -> GAME_OVER (hold a fist to replay).
 */
class RpsGame(private val listener: Listener) {

    interface Listener {
        fun onSfx(sfx: Sfx)
        fun onBeat()
        fun onPhaseChanged(phase: Phase)
    }

    enum class Phase { WAIT_START, INTRO, CHANT, NO_HAND, REVEAL, GAME_OVER }
    enum class Outcome { WIN, LOSE, DRAW }

    var phase = Phase.WAIT_START
        private set
    var phaseStart = 0L
        private set
    var playerScore = 0
        private set
    var aiScore = 0
        private set
    var round = 1
        private set
    var playerMove: RpsMove? = null
        private set
    var aiMove: RpsMove? = null
        private set
    var outcome: Outcome? = null
        private set
    var holdProgress = 0f
        private set
    var scoreChangedAt = 0L
        private set
    var lastScorer: Outcome? = null
        private set

    val playerWonGame: Boolean get() = playerScore > aiScore

    private var started = false
    private var fistSince = 0L
    private var lastFistSeen = 0L
    private var lastBeat = -1
    private val votes = FloatArray(3)

    fun update(now: Long, move: RpsMove?) {
        if (!started) {
            started = true
            enter(Phase.WAIT_START, now)
        }
        val t = now - phaseStart
        when (phase) {
            Phase.WAIT_START -> trackHold(now, move)

            Phase.INTRO -> {
                if (t >= INTRO_BEAT_MS * 3) {
                    enter(Phase.CHANT, now)
                    return
                }
                beat((t / INTRO_BEAT_MS).toInt(), Sfx.TICK)
            }

            Phase.CHANT -> {
                if (t < CHANT_BEAT_MS * 3) {
                    beat((t / CHANT_BEAT_MS).toInt(), if (t >= CHANT_BEAT_MS * 2) Sfx.SHOOT else Sfx.BEAT)
                }
                // capture the throw from "Scissors!" onwards; later frames count more (final shape wins)
                if (t in CAPTURE_START_MS until CAPTURE_END_MS && move != null) {
                    votes[move.ordinal] += 1f + (t - CAPTURE_START_MS) / 200f
                }
                if (t >= CAPTURE_END_MS) resolve(now)
            }

            Phase.NO_HAND -> if (t >= NO_HAND_MS) enter(Phase.CHANT, now)

            Phase.REVEAL -> if (t >= REVEAL_MS) {
                if (playerScore >= WIN_SCORE || aiScore >= WIN_SCORE) {
                    enter(Phase.GAME_OVER, now)
                    listener.onSfx(if (playerWonGame) Sfx.GAME_WIN else Sfx.GAME_LOSE)
                } else {
                    round++
                    enter(Phase.CHANT, now)
                }
            }

            Phase.GAME_OVER -> if (t >= RESTART_DELAY_MS) trackHold(now, move)
        }
    }

    fun startGame(now: Long) {
        started = true
        playerScore = 0
        aiScore = 0
        round = 1
        playerMove = null
        aiMove = null
        outcome = null
        lastScorer = null
        scoreChangedAt = 0L
        enter(Phase.INTRO, now)
    }

    private fun trackHold(now: Long, move: RpsMove?) {
        if (move == RpsMove.ROCK) {
            if (fistSince == 0L) fistSince = now
            lastFistSeen = now
        } else if (fistSince != 0L && now - lastFistSeen > HOLD_GRACE_MS) {
            fistSince = 0L
        }
        holdProgress = if (fistSince == 0L) 0f else ((now - fistSince).toFloat() / HOLD_MS).coerceAtMost(1f)

        val second = if (fistSince == 0L) -1 else ((now - fistSince) / 1000).toInt()
        if (second != lastBeat) {
            if (second in 1..2) listener.onSfx(Sfx.TICK)
            lastBeat = second
        }
        if (holdProgress >= 1f) startGame(now)
    }

    private fun beat(index: Int, sfx: Sfx) {
        if (index == lastBeat) return
        lastBeat = index
        listener.onSfx(sfx)
        listener.onBeat()
    }

    private fun resolve(now: Long) {
        var best = -1
        var bestVotes = 0f
        for (i in votes.indices) {
            if (votes[i] > bestVotes) {
                bestVotes = votes[i]
                best = i
            }
        }
        if (best < 0) {
            listener.onSfx(Sfx.DRAW)
            enter(Phase.NO_HAND, now)
            return
        }

        val player = RpsMove.entries[best]
        val ai = RpsMove.entries[Random.nextInt(3)] // picked only after the player's throw is locked
        playerMove = player
        aiMove = ai
        val o = when {
            player == ai -> Outcome.DRAW
            player.beats(ai) -> Outcome.WIN
            else -> Outcome.LOSE
        }
        outcome = o
        when (o) {
            Outcome.WIN -> playerScore++
            Outcome.LOSE -> aiScore++
            Outcome.DRAW -> Unit
        }
        if (o != Outcome.DRAW) {
            lastScorer = o
            scoreChangedAt = now + SCORE_ANIM_DELAY_MS
        }
        listener.onSfx(
            when (o) {
                Outcome.WIN -> Sfx.ROUND_WIN
                Outcome.LOSE -> Sfx.ROUND_LOSE
                Outcome.DRAW -> Sfx.DRAW
            }
        )
        enter(Phase.REVEAL, now)
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseStart = now
        lastBeat = -1
        votes.fill(0f)
        fistSince = 0L
        holdProgress = 0f
        listener.onPhaseChanged(p)
    }

    companion object {
        const val HOLD_MS = 3000L
        const val HOLD_GRACE_MS = 300L
        const val INTRO_BEAT_MS = 800L
        const val CHANT_BEAT_MS = 600L
        const val CAPTURE_START_MS = 1200L
        const val CAPTURE_END_MS = 2050L
        const val NO_HAND_MS = 1700L
        const val REVEAL_MS = 2300L
        const val RESTART_DELAY_MS = 1500L
        const val SCORE_ANIM_DELAY_MS = 350L
        const val WIN_SCORE = 2
    }
}

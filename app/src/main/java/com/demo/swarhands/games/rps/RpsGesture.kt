package com.demo.swarhands.games.rps

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

enum class RpsMove(val label: String, val emoji: String) {
    ROCK("Stone", "✊"),
    PAPER("Paper", "✋"),
    SCISSORS("Scissors", "✌️");

    // pre-built strings so the game view never allocates text while drawing
    val youText = "You: $emoji $label"
    val aiText = "AI: $emoji $label"

    fun beats(other: RpsMove): Boolean =
        (this == ROCK && other == SCISSORS) ||
            (this == SCISSORS && other == PAPER) ||
            (this == PAPER && other == ROCK)
}

/**
 * Reads stone / paper / scissors from the 21 hand landmarks.
 * A finger counts as "open" when its tip is clearly farther from the wrist than its middle joint (PIP).
 */
object RpsClassifier {
    private val TIPS = intArrayOf(8, 12, 16, 20)  // index, middle, ring, pinky
    private val PIPS = intArrayOf(6, 10, 14, 18)
    private const val OPEN_RATIO = 1.12f

    fun classify(lm: List<NormalizedLandmark>, imgW: Int, imgH: Int): RpsMove? {
        if (lm.size < 21) return null
        val wx = lm[0].x() * imgW
        val wy = lm[0].y() * imgH
        fun distToWrist(i: Int): Float {
            val dx = lm[i].x() * imgW - wx
            val dy = lm[i].y() * imgH - wy
            return sqrt(dx * dx + dy * dy)
        }

        var mask = 0
        var open = 0
        for (f in 0..3) {
            if (distToWrist(TIPS[f]) > distToWrist(PIPS[f]) * OPEN_RATIO) {
                mask = mask or (1 shl f)
                open++
            }
        }
        return when {
            open == 0 -> RpsMove.ROCK
            mask == 0b0011 -> RpsMove.SCISSORS                         // index + middle only
            open == 4 -> RpsMove.PAPER
            open == 3 && (mask and 0b0011) == 0b0011 -> RpsMove.PAPER  // forgive one lazy finger
            else -> null
        }
    }
}

/** Majority vote over the last few frames so a single noisy frame can't flip the gesture. */
class RpsSmoother(private val window: Int = 5, private val needed: Int = 3) {
    private val history = arrayOfNulls<RpsMove>(window)
    private val counts = IntArray(3)
    private var index = 0

    fun push(move: RpsMove?): RpsMove? {
        history[index] = move
        index = (index + 1) % window
        counts.fill(0)
        for (m in history) if (m != null) counts[m.ordinal]++
        var best = -1
        for (i in counts.indices) if (counts[i] >= needed && (best < 0 || counts[i] > counts[best])) best = i
        return if (best >= 0) RpsMove.entries[best] else null
    }
}

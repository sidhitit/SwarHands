package com.demo.swarhands.games.signs

import android.graphics.PointF
import com.demo.swarhands.gesture.ViewTransform
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot

enum class SignKind { HAND, TWO_HANDS }

/**
 * Everything the game can ask for. [tier] = how late it unlocks (0 from the start … 3 late).
 * [number] > 0 shows a number badge on the card; [hint] is the small line under the title.
 */
enum class Sign(val title: String, val tier: Int, val number: Int, val kind: SignKind = SignKind.HAND, val hint: String = "") {
    ONE("One", 0, 1),
    TWO("Two", 0, 2),
    THREE("Three", 0, 3),
    FIVE("Five", 0, 5),
    FIST("Fist", 0, 0),
    THUMBS_UP("Thumbs up", 0, 0),
    FOUR("Four", 1, 4),
    THUMBS_DOWN("Thumbs down", 1, 0),
    ROCK("Rock on", 1, 0),
    CALL("Call me", 2, 0),
    OK("OK", 2, 0),
    // two hands
    SIX("Six", 2, 6, SignKind.TWO_HANDS, "Both hands: 5 + 1"),
    SEVEN("Seven", 2, 7, SignKind.TWO_HANDS, "Both hands: 5 + 2"),
    EIGHT("Eight", 3, 8, SignKind.TWO_HANDS, "Both hands: 5 + 3"),
    NINE("Nine", 3, 9, SignKind.TWO_HANDS, "Both hands: 5 + 4"),
    TEN("Ten", 3, 10, SignKind.TWO_HANDS, "Both hands open"),
    HEART("Hand heart", 3, 0, SignKind.TWO_HANDS, "Thumbs and index fingers touching");

    companion object {
        private val BY_FINGERS = arrayOf(null, ONE, TWO, THREE, FOUR, FIVE)
        private val BY_TOTAL = arrayOf(SIX, SEVEN, EIGHT, NINE, TEN)

        /** The one-hand sign showing [n] fingers (1..5). */
        fun fingers(n: Int): Sign? = BY_FINGERS.getOrNull(n)

        /** 5 on one hand plus [other] on the second: SIX … TEN. */
        fun total(other: Sign?): Sign? = other?.number?.takeIf { it in 1..5 && other.kind == SignKind.HAND }
            ?.let { BY_TOTAL[it - 1] }
    }
}

/**
 * Reads a one-hand [Sign] from the 21 hand landmarks (view pixels).
 *
 * A finger counts as up when its tip is clearly farther from the wrist than its middle joint,
 * which works whatever way the hand is turned. The thumb counts as out when its tip is well
 * away from the palm centre; its direction tells thumbs up from thumbs down.
 * Poses in between return null instead of guessing.
 */
object SignClassifier {
    private val TIPS = intArrayOf(8, 12, 16, 20)  // index, middle, ring, pinky
    private val PIPS = intArrayOf(6, 10, 14, 18)
    private const val UP_RATIO = 1.12f
    private const val THUMB_OUT = 0.75f    // thumb tip to palm centre, in hand sizes
    private const val OK_TOUCH = 0.32f     // thumb tip to index tip, in hand sizes
    private const val THUMB_VERTICAL = 0.55f
    private const val HEART_TOUCH = 0.6f   // tips of both hands meeting, in hand sizes

    fun classify(p: List<PointF>): Sign? {
        if (p.size < 21) return null
        val size = d(p[0], p[9]).coerceAtLeast(1f)
        var mask = 0
        for (f in 0..3) if (d(p[0], p[TIPS[f]]) > d(p[0], p[PIPS[f]]) * UP_RATIO) mask = mask or (1 shl f)

        val cx = (p[0].x + p[5].x + p[9].x + p[13].x + p[17].x) / 5f
        val cy = (p[0].y + p[5].y + p[9].y + p[13].y + p[17].y) / 5f
        val thumbOut = hypot(p[4].x - cx, p[4].y - cy) / size > THUMB_OUT

        // OK: thumb and index tips touch, the other three fingers up
        if (d(p[4], p[8]) / size < OK_TOUCH && (mask and 0b1110) == 0b1110) return Sign.OK

        return when (mask) {
            0b0000 -> if (!thumbOut) Sign.FIST else {
                val dx = p[4].x - p[2].x
                val dy = p[4].y - p[2].y
                val dl = hypot(dx, dy).coerceAtLeast(1f)
                when {
                    dy / dl < -THUMB_VERTICAL -> Sign.THUMBS_UP
                    dy / dl > THUMB_VERTICAL -> Sign.THUMBS_DOWN
                    else -> null
                }
            }
            0b0001 -> Sign.ONE
            0b0011 -> Sign.TWO
            0b0111 -> Sign.THREE
            0b1111 -> if (thumbOut) Sign.FIVE else Sign.FOUR
            0b1001 -> Sign.ROCK
            0b1000 -> if (thumbOut) Sign.CALL else null
            else -> null
        }
    }

    /**
     * Two hands at once: the 🫶 heart (both thumb tips and both index tips meet, index tips on
     * top), otherwise 5 + n for SIX … TEN, otherwise whichever single hand shows a sign.
     */
    fun classifyTwo(a: List<PointF>, b: List<PointF>, sa: Sign?, sb: Sign?): Sign? {
        val size = (d(a[0], a[9]) + d(b[0], b[9])) / 2f
        val thumbs = d(a[4], b[4]) / size
        val index = d(a[8], b[8]) / size
        val indexY = (a[8].y + b[8].y) / 2f
        val thumbY = (a[4].y + b[4].y) / 2f
        if (thumbs < HEART_TOUCH && index < HEART_TOUCH && thumbY - indexY > size * 0.5f &&
            abs(a[0].x - b[0].x) > size * 0.5f
        ) return Sign.HEART
        if (sa == Sign.FIVE) Sign.total(sb)?.let { return it }
        if (sb == Sign.FIVE) Sign.total(sa)?.let { return it }
        return when {
            sa != null && sb == null -> sa
            sb != null && sa == null -> sb
            else -> null
        }
    }

    private fun d(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
}

/** Snapshot from the tracking thread to the view. [partial] = a hand is cut off by the screen edge. */
class SignState(val hands: List<List<PointF>>, val sign: Sign?, val partial: Boolean)

/**
 * Hand landmarks -> a steady [Sign]. Runs on the tracking thread only.
 * Majority vote over the last few frames, so one noisy frame can't flip the sign.
 */
class SignTracker {
    private val history = arrayOfNulls<Sign>(WINDOW)
    private val counts = IntArray(Sign.entries.size)
    private var index = 0

    fun process(result: HandLandmarkerResult, t: ViewTransform): SignState {
        val hands = result.landmarks().filter { it.size >= 21 }.take(2).map { lm -> lm.map { t.map(it.x(), it.y()) } }
        // a hand cut off by the screen edge has guessed fingertips: don't read it
        val m = t.viewW * EDGE
        val partial = hands.any { h -> h.any { it.x < -m || it.x > t.viewW + m || it.y < -m || it.y > t.viewH + m } }

        val raw: Sign? = when {
            partial || hands.isEmpty() -> null
            hands.size == 1 -> SignClassifier.classify(hands[0])
            else -> SignClassifier.classifyTwo(
                hands[0], hands[1], SignClassifier.classify(hands[0]), SignClassifier.classify(hands[1])
            )
        }

        history[index] = raw
        index = (index + 1) % WINDOW
        counts.fill(0)
        for (s in history) if (s != null) counts[s.ordinal]++
        var best = -1
        for (i in counts.indices) if (counts[i] >= NEEDED && (best < 0 || counts[i] > counts[best])) best = i
        return SignState(hands, if (best >= 0 && raw != null) Sign.entries[best] else null, partial)
    }

    private companion object {
        const val WINDOW = 6
        const val NEEDED = 4
        const val EDGE = 0.01f
    }
}

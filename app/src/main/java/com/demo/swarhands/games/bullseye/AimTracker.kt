package com.demo.swarhands.games.bullseye

import android.graphics.PointF
import android.os.SystemClock
import com.demo.swarhands.gesture.ViewTransform
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** One released shot. Direction is a unit vector in screen space (up = into the range). */
class Shot(val power: Float, val dirX: Float, val dirY: Float, val fromX: Float, val fromY: Float)

/** Immutable snapshot from the tracking thread to the view. All positions are view pixels. */
class AimState(
    val hands: List<List<PointF>>,
    val gripVisible: Boolean,
    val gripX: Float,              // bow grip on its fixed line, or the slingshot fork
    val gripY: Float,
    val drawing: Boolean,
    val pullX: Float,              // string nock, or the slingshot pouch
    val pullY: Float,
    val power: Float,              // 0..MAX_POWER, above 1 = overpower
    val dirX: Float,
    val dirY: Float,
    val releaseSeq: Int,           // increments on every release; [shot] is the latest one
    val shot: Shot?
)

/**
 * Turns hand landmarks into draw / aim / release. Runs on the tracking thread only.
 *
 * Slingshot (1 hand): pinch thumb + index anywhere, then pull. The pull vector is applied to the
 *   pouch at the fixed fork, power = pull length / a reach based on your hand size.
 * Bow (2 hands): the bow slides along a fixed line, steered left/right by one hand's two
 *   fingers and rotated up to ~50 degrees each way by leaning those fingers (wrist still).
 *   The other hand pinches the string at the bow and pulls down; the arrow flies where the bow points.
 * Release = open the pinch. The shot uses the aim from a couple of frames earlier, because
 * opening the fingers shifts the pinch point and would otherwise yank the aim.
 */
class AimTracker(private val weapon: Weapon) {

    private class Hand(val pts: List<PointF>, val label: String?) {
        val size = hypot(pts[0].x - pts[9].x, pts[0].y - pts[9].y).coerceAtLeast(1f)
        val pinchX = (pts[4].x + pts[8].x) / 2f
        val pinchY = (pts[4].y + pts[8].y) / 2f
        val ratio = hypot(pts[4].x - pts[8].x, pts[4].y - pts[8].y) / size
        val gripX = (pts[0].x + pts[5].x + pts[9].x + pts[13].x + pts[17].x) / 5f
        val gripY = (pts[0].y + pts[5].y + pts[9].y + pts[13].y + pts[17].y) / 5f
        val steerX = (pts[8].x + pts[12].x) / 2f
        /** Angle of wrist -> two fingertips from straight up (radians, + = leaning right). */
        val tilt = atan2(steerX - pts[0].x, pts[0].y - (pts[8].y + pts[12].y) / 2f)
        /** Index and middle up, ring folded: the ✌ steering pose. */
        val twoUp = pts[8].y < pts[6].y && pts[12].y < pts[10].y && pts[16].y > pts[14].y
    }

    /** Light adaptive smoothing: steady when still, responsive when moving fast. */
    private class Smooth {
        var x = 0f
        var y = 0f
        var has = false
        fun push(px: Float, py: Float, scale: Float) {
            if (!has) {
                x = px; y = py; has = true
                return
            }
            val a = (0.3f + hypot(px - x, py - y) / scale * 2.5f).coerceIn(0.3f, 0.85f)
            x += (px - x) * a
            y += (py - y) * a
        }
    }

    private var drawing = false
    private var lostSince = 0L
    private var originX = 0f
    private var originY = 0f
    private var maxPull = 1f
    private var lastDrawX = 0f
    private var lastDrawY = 0f
    private val pull = Smooth()
    private val steer = Smooth()
    private val tilt = Smooth()

    // last computed aim
    private var power = 0f
    private var dirX = 0f
    private var dirY = -1f
    private var pullX = 0f
    private var pullY = 0f

    // ring buffer: power, dirX, dirY, fromX, fromY
    private val hist = Array(HISTORY) { FloatArray(5) }
    private var histPos = 0
    private var histCount = 0

    private var releaseSeq = 0
    private var lastShot: Shot? = null

    fun process(result: HandLandmarkerResult, t: ViewTransform, anchorX: Float, anchorY: Float): AimState {
        val now = SystemClock.uptimeMillis()
        val labels = result.handedness()
        val hands = ArrayList<Hand>(2)
        result.landmarks().forEachIndexed { i, lm ->
            if (lm.size >= 21) {
                hands += Hand(lm.map { t.map(it.x(), it.y()) }, labels.getOrNull(i)?.firstOrNull()?.categoryName())
            }
        }
        val pts = hands.map { it.pts }
        return if (weapon == Weapon.SLINGSHOT) sling(hands, pts, now, t.viewW, anchorX, anchorY)
        else bow(hands, pts, now, t.viewW, anchorX, anchorY)
    }

    // ---------------------------------------------------------------- slingshot

    private fun sling(
        hands: List<Hand>, pts: List<List<PointF>>, now: Long, viewW: Int, ax: Float, ay: Float
    ): AimState {
        val hand = hands.firstOrNull()
        if (hand == null) {
            if (drawing && lost(now)) drawing = false
        } else {
            lostSince = 0L
            if (!drawing && hand.ratio < PINCH_ON) {
                drawing = true
                originX = hand.pinchX
                originY = hand.pinchY
                maxPull = (hand.size * 2.4f).coerceIn(viewW * 0.2f, viewW * 0.45f)
                pull.has = false
                histCount = 0
            } else if (drawing && hand.ratio > PINCH_OFF) {
                release()
            }
            if (drawing) {
                pull.push(hand.pinchX, hand.pinchY, hand.size)
                aim(pull.x - originX, pull.y - originY, ax, ay)
            }
        }
        if (!drawing) rest(ax, ay)
        return state(pts, true, ax, ay)
    }

    // ---------------------------------------------------------------- bow

    /**
     * The bow sits on a fixed horizontal line at y = [by]. One hand steers it left/right
     * (the point between its index and middle fingertips); the other pinches the string at
     * the bow and pulls straight down. Arrows always fly straight up from the bow.
     */
    private fun bow(hands: List<Hand>, pts: List<List<PointF>>, now: Long, viewW: Int, bx0: Float, by: Float): AimState {
        val minX = viewW * RAIL_MIN // keeps the whole bow on screen
        val maxX = viewW * RAIL_MAX

        if (!drawing) {
            steerHand(hands)?.let { steerWith(it) }
            val bx = bowX(bx0, minX, maxX)
            if (hands.size == 2) {
                // nock: a pinch close to the string (the red dot under the bow)
                for (d in hands) {
                    if (d.ratio < PINCH_ON && hypot(d.pinchX - bx, d.pinchY - by) < maxOf(d.size * 2f, viewW * 0.18f)) {
                        drawing = true
                        originY = d.pinchY
                        maxPull = (d.size * 2.4f).coerceIn(viewW * 0.2f, viewW * 0.45f)
                        lastDrawX = d.pinchX
                        lastDrawY = d.pinchY
                        pull.has = false
                        histCount = 0
                        break
                    }
                }
            }
            if (!drawing) {
                restTilted(bx, by)
                return state(pts, true, bx, by)
            }
        }

        val bx = bowX(bx0, minX, maxX)
        if (hands.size < 2) {
            if (lost(now)) {
                drawing = false
                restTilted(bx, by)
            }
            return state(pts, true, bx, by)
        }
        lostSince = 0L

        // follow the drawing hand by position: handedness labels can flip mid-draw
        val d = if (dist2(hands[0]) <= dist2(hands[1])) hands[0] else hands[1]
        val b = if (d === hands[0]) hands[1] else hands[0]
        steerWith(b)
        val x = bowX(bx0, minX, maxX)
        if (d.ratio > PINCH_OFF) {
            release()
            restTilted(x, by)
            return state(pts, true, x, by)
        }
        lastDrawX = d.pinchX
        lastDrawY = d.pinchY
        pull.push(d.pinchX, d.pinchY, d.size)
        aimAlong(maxOf(0f, pull.y - originY), x, by) // only pulling down counts
        return state(pts, true, x, by)
    }

    /** Slide the bow with the two fingers' position, rotate it with their lean (up to ±[MAX_TILT]). */
    private fun steerWith(h: Hand) {
        steer.push(h.steerX, 0f, h.size)
        var a = h.tilt.coerceIn(-MAX_TILT, MAX_TILT)
        a = if (abs(a) < TILT_DEAD_ZONE) 0f else a - sign(a) * TILT_DEAD_ZONE // easy straight shots
        tilt.push(a * MAX_TILT / (MAX_TILT - TILT_DEAD_ZONE), 0f, 1f)
    }

    private fun bowDir() {
        val a = if (tilt.has) tilt.x else 0f
        dirX = sin(a)
        dirY = -cos(a)
    }

    private fun restTilted(bx: Float, by: Float) {
        rest(bx, by)
        bowDir()
    }

    /** Bow: [dist] pulled down -> power; the arrow points where the bow is tilted. */
    private fun aimAlong(dist: Float, bx: Float, by: Float) {
        bowDir()
        power = min(dist / maxPull, MAX_POWER)
        val shown = min(dist, maxPull * MAX_POWER)
        pullX = bx - dirX * shown
        pullY = by - dirY * shown
        pushHistory(bx, by)
    }

    private fun bowX(fallback: Float, minX: Float, maxX: Float) =
        if (steer.has) steer.x.coerceIn(minX, maxX) else fallback

    private fun dist2(h: Hand): Float {
        val dx = h.pinchX - lastDrawX
        val dy = h.pinchY - lastDrawY
        return dx * dx + dy * dy
    }

    /** The steering hand: the one showing two fingers, else the open one, else the one already steering. */
    private fun steerHand(hands: List<Hand>): Hand? = when (hands.size) {
        0 -> null
        1 -> hands[0]
        else -> {
            val a = hands[0]
            val b = hands[1]
            when {
                a.twoUp != b.twoUp -> if (a.twoUp) a else b
                (a.ratio < PINCH_ON) != (b.ratio < PINCH_ON) -> if (a.ratio < PINCH_ON) b else a
                !steer.has -> hands.firstOrNull { it.label == "Left" } ?: a
                else -> if (abs(a.steerX - steer.x) <= abs(b.steerX - steer.x)) a else b
            }
        }
    }

    // ---------------------------------------------------------------- shared

    private fun lost(now: Long): Boolean {
        if (lostSince == 0L) lostSince = now
        return now - lostSince > LOST_MS
    }

    /** Pull vector (vx, vy) measured from the base point (bx, by) -> power, direction, pouch/nock. */
    private fun aim(vx: Float, vy: Float, bx: Float, by: Float) {
        val len = hypot(vx, vy)
        power = min(len / maxPull, MAX_POWER)
        if (len > 1f) {
            dirX = -vx / len
            dirY = -vy / len
        }
        val shown = min(len, maxPull * MAX_POWER)
        pullX = bx - dirX * shown
        pullY = by - dirY * shown
        pushHistory(bx, by)
    }

    private fun pushHistory(bx: Float, by: Float) {
        val h = hist[histPos]
        h[0] = power; h[1] = dirX; h[2] = dirY; h[3] = bx; h[4] = by
        histPos = (histPos + 1) % HISTORY
        histCount = min(histCount + 1, HISTORY)
    }

    private fun rest(bx: Float, by: Float) {
        power = 0f
        dirX = 0f
        dirY = -1f
        pullX = bx
        pullY = by
    }

    private fun release() {
        drawing = false
        if (histCount == 0) return
        val back = min(histCount, RELEASE_LOOKBACK)
        val h = hist[(histPos - back + HISTORY) % HISTORY]
        if (h[0] < MIN_SHOT_POWER) return // barely pulled: just let go, no shot wasted
        releaseSeq++
        lastShot = Shot(h[0], h[1], h[2], h[3], h[4])
    }

    private fun state(pts: List<List<PointF>>, gripVisible: Boolean, gx: Float, gy: Float) = AimState(
        pts, gripVisible, gx, gy, drawing, pullX, pullY, power, dirX, dirY, releaseSeq, lastShot
    )

    companion object {
        const val MAX_POWER = 1.3f
        const val RAIL_MIN = 0.22f
        const val RAIL_MAX = 0.78f
        private const val MAX_TILT = 0.87f        // ~50 degrees each way
        private const val TILT_DEAD_ZONE = 0.07f  // ~4 degrees
        private const val PINCH_ON = 0.33f
        private const val PINCH_OFF = 0.48f
        private const val LOST_MS = 350L
        private const val MIN_SHOT_POWER = 0.15f
        private const val HISTORY = 6
        private const val RELEASE_LOOKBACK = 2
    }
}

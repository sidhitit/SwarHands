package com.demo.swarhands.games.cricket

import android.graphics.PointF
import android.os.SystemClock
import com.demo.swarhands.gesture.ViewTransform
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One bat swing. [at] = uptime of the swing's fastest moment; direction is a unit vector (screen). */
class Swing(val at: Long, val power: Float, val dirX: Float, val dirY: Float)

/** Immutable snapshot from the tracking thread to the view. Positions are view pixels. */
class BatState(
    val hand: List<PointF>?,       // null = no hand in view
    val gripX: Float,              // bat handle (palm)
    val gripY: Float,
    val tipX: Float,               // end of the blade
    val tipY: Float,
    val width: Float,              // blade width
    val speed: Float,              // hand speed, view widths per second
    val swingSeq: Int,             // increments on every swing; [swing] is the latest one
    val swing: Swing?
)

/**
 * Turns one hand into a cricket bat. Runs on the tracking thread only.
 *
 * The bat grows out of the palm along the fingers, so it follows the hand naturally.
 * A swing is a burst of hand speed: it starts when the palm moves faster than [SWING_ON]
 * and is reported at its peak, with power from the peak speed. After a swing the hand has
 * to slow down before the next one counts, so one stroke is never reported twice.
 * Speeds are measured over [VEL_MS], so it behaves the same at 30 or 60 fps.
 */
class BatTracker {

    private val hx = FloatArray(HISTORY)
    private val hy = FloatArray(HISTORY)
    private val ht = LongArray(HISTORY)
    private var pos = 0
    private var count = 0

    private var armed = true
    private var swinging = false
    private var onsetAt = 0L
    private var peak = 0f
    private var peakAt = 0L
    private var peakVx = 0f
    private var peakVy = 0f

    private var seq = 0
    private var last: Swing? = null

    // smoothed bat drawing
    private var gx = 0f
    private var gy = 0f
    private var tx = 0f
    private var ty = 0f
    private var hasBat = false

    fun process(result: HandLandmarkerResult, t: ViewTransform): BatState {
        val now = SystemClock.uptimeMillis()
        val lm = result.landmarks().firstOrNull()?.takeIf { it.size >= 21 }
        if (lm == null) {
            count = 0
            swinging = false
            armed = true
            return BatState(null, gx, gy, tx, ty, 0f, 0f, seq, last)
        }
        val pts = lm.map { t.map(it.x(), it.y()) }
        val w = t.viewW.toFloat()

        // palm centre, measured in view widths on both axes
        val px = (pts[0].x + pts[5].x + pts[9].x + pts[13].x + pts[17].x) / 5f
        val py = (pts[0].y + pts[5].y + pts[9].y + pts[13].y + pts[17].y) / 5f
        push(now, px / w, py / w)

        // velocity over the last ~VEL_MS
        var speed = 0f
        var vx = 0f
        var vy = 0f
        if (count >= 2) {
            val newest = (pos - 1 + HISTORY) % HISTORY
            var old = newest
            for (i in 1 until count) {
                val j = (newest - i + HISTORY) % HISTORY
                old = j
                if (now - ht[j] >= VEL_MS) break
            }
            val dt = (ht[newest] - ht[old]) / 1000f
            if (dt > 0.005f) {
                vx = (hx[newest] - hx[old]) / dt
                vy = (hy[newest] - hy[old]) / dt
                speed = hypot(vx, vy)
            }
        }

        if (!swinging) {
            if (armed && speed > SWING_ON) {
                swinging = true
                onsetAt = now
                peak = speed
                peakAt = now
                peakVx = vx
                peakVy = vy
            } else if (speed < SWING_ON * REARM) {
                armed = true
            }
        } else {
            if (speed > peak) {
                peak = speed
                peakAt = now
                peakVx = vx
                peakVy = vy
            }
            if (speed < peak * PEAK_DROP || now - onsetAt > PEAK_MAX_MS) {
                swinging = false
                armed = false
                val len = max(hypot(peakVx, peakVy), 1e-4f)
                val power = ((peak - SWING_ON) / (POWER_FULL - SWING_ON)).coerceIn(0f, 1f)
                seq++
                last = Swing(peakAt, power, peakVx / len, peakVy / len)
            }
        }

        // bat: handle in the palm, blade along wrist -> middle fingertip
        val size = hypot(pts[0].x - pts[9].x, pts[0].y - pts[9].y).coerceAtLeast(1f)
        var dx = pts[12].x - pts[0].x
        var dy = pts[12].y - pts[0].y
        val dl = max(hypot(dx, dy), 1f)
        dx /= dl
        dy /= dl
        val len = min(size * 3.4f, w * 0.5f)
        val ngx = px
        val ngy = py
        val ntx = px + dx * len
        val nty = py + dy * len
        if (!hasBat) {
            gx = ngx; gy = ngy; tx = ntx; ty = nty
            hasBat = true
        } else {
            // follow fast moves at once, steady the small jitter
            val a = (0.45f + speed * 0.3f).coerceAtMost(1f)
            gx += (ngx - gx) * a; gy += (ngy - gy) * a
            tx += (ntx - tx) * a; ty += (nty - ty) * a
        }
        return BatState(pts, gx, gy, tx, ty, size * 0.62f, speed, seq, last)
    }

    private fun push(now: Long, x: Float, y: Float) {
        hx[pos] = x
        hy[pos] = y
        ht[pos] = now
        pos = (pos + 1) % HISTORY
        count = min(count + 1, HISTORY)
    }

    companion object {
        /** Palm speed (view widths per second) that counts as a swing. */
        const val SWING_ON = 1.5f
        /** Palm speed for a full-power swing. */
        const val POWER_FULL = 4.2f
        private const val REARM = 0.5f        // slow below SWING_ON * this before the next swing
        private const val PEAK_DROP = 0.75f   // the swing peaked once speed falls below this share
        private const val PEAK_MAX_MS = 160L
        private const val VEL_MS = 80L
        private const val HISTORY = 16
    }
}

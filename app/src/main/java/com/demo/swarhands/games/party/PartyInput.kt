package com.demo.swarhands.games.party

import android.graphics.PointF
import android.os.SystemClock
import com.demo.swarhands.games.signs.Sign
import com.demo.swarhands.games.signs.SignTracker
import com.demo.swarhands.gesture.ViewTransform
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot
import kotlin.math.min

/**
 * Everything a microgame may want to know about the player's hand this frame (view pixels).
 * Built on the tracking thread; microgames only read it, so they never touch tracking code.
 */
class HandFrame(
    val hands: List<List<PointF>>,
    val partial: Boolean,      // a hand is cut off by the screen edge
    val sign: Sign?,           // steady sign (Sign Says' reader)
    val palmX: Float,          // main hand
    val palmY: Float,
    val tipX: Float,           // index fingertip
    val tipY: Float,
    val pinching: Boolean,
    val pinchX: Float,
    val pinchY: Float,
    val speed: Float           // palm speed, view widths per second
) {
    val visible get() = hands.isNotEmpty()
}

/**
 * Hand landmarks -> [HandFrame]. Runs on the tracking thread only.
 * The sign comes from Sign Says' tracker; pinch uses a hysteresis on thumb-index distance
 * (like Bullseye) and speed is measured over [VEL_MS] (like Gully Cricket), so it behaves
 * the same at 30 or 60 fps.
 */
class PartyTracker {
    private val signs = SignTracker()

    private val hx = FloatArray(HISTORY)
    private val hy = FloatArray(HISTORY)
    private val ht = LongArray(HISTORY)
    private var pos = 0
    private var count = 0
    private var pinching = false
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    fun process(result: HandLandmarkerResult, t: ViewTransform): HandFrame {
        val now = SystemClock.uptimeMillis()
        val s = signs.process(result, t)
        val w = t.viewW.toFloat()
        if (s.hands.isEmpty()) {
            count = 0
            pinching = false
            return HandFrame(s.hands, s.partial, null, lastX, lastY, lastX, lastY, false, lastX, lastY, 0f)
        }
        // the main hand: the one nearest to where the main hand was, so it doesn't jump between hands
        val p = if (s.hands.size == 1 || lastX.isNaN()) s.hands[0] else s.hands.minBy { h ->
            val (x, y) = palm(h)
            hypot(x - lastX, y - lastY)
        }
        val (px, py) = palm(p)
        lastX = px
        lastY = py

        hx[pos] = px / w
        hy[pos] = py / w
        ht[pos] = now
        pos = (pos + 1) % HISTORY
        count = min(count + 1, HISTORY)
        var speed = 0f
        if (count >= 2) {
            val newest = (pos - 1 + HISTORY) % HISTORY
            var old = newest
            for (i in 1 until count) {
                old = (newest - i + HISTORY) % HISTORY
                if (now - ht[old] >= VEL_MS) break
            }
            val dt = (ht[newest] - ht[old]) / 1000f
            if (dt > 0.005f) speed = hypot(hx[newest] - hx[old], hy[newest] - hy[old]) / dt
        }

        val size = hypot(p[0].x - p[9].x, p[0].y - p[9].y).coerceAtLeast(1f)
        val ratio = hypot(p[4].x - p[8].x, p[4].y - p[8].y) / size
        pinching = if (pinching) ratio < PINCH_OFF else ratio < PINCH_ON

        return HandFrame(
            s.hands, s.partial, s.sign, px, py, p[8].x, p[8].y,
            pinching, (p[4].x + p[8].x) / 2f, (p[4].y + p[8].y) / 2f, speed
        )
    }

    private fun palm(h: List<PointF>): Pair<Float, Float> =
        (h[0].x + h[5].x + h[9].x + h[13].x + h[17].x) / 5f to (h[0].y + h[5].y + h[9].y + h[13].y + h[17].y) / 5f

    private companion object {
        const val HISTORY = 16
        const val VEL_MS = 100L
        const val PINCH_ON = 0.33f
        const val PINCH_OFF = 0.48f
    }
}

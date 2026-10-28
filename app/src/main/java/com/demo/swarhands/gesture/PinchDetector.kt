package com.demo.swarhands.gesture

import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects thumb-to-fingertip pinches.
 *
 * Distances are divided by the hand size (wrist -> middle MCP), so it works whether the
 * hand is near or far from the camera. Two thresholds (hysteresis) stop flickering.
 */
class PinchDetector(
    private val pinchOnRatio: Float = 0.30f,   // lower = must touch more firmly
    private val pinchOffRatio: Float = 0.42f,  // must open past this to release
    private val minRetriggerMs: Long = 90L
) {
    data class Result(val current: Set<Finger>, val newlyPinched: List<Finger>)

    private val pinchedByHand = HashMap<String, MutableSet<Finger>>()
    private val lastOnsetByHand = HashMap<String, Long>()

    fun update(handKey: String, lm: List<NormalizedLandmark>, imgW: Int, imgH: Int): Result {
        fun dist(a: Int, b: Int): Float {
            val p = lm[a]
            val q = lm[b]
            val dx = (p.x() - q.x()) * imgW
            val dy = (p.y() - q.y()) * imgH
            val dz = (p.z() - q.z()) * imgW * 0.5f // z is noisy, weight it down
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        val handSize = max(dist(HandSkeleton.WRIST, HandSkeleton.MIDDLE_MCP), 1f)
        val ratios = Finger.entries.associateWith { dist(HandSkeleton.THUMB_TIP, it.tipIndex) / handSize }
        val pinched = pinchedByHand.getOrPut(handKey) { mutableSetOf() }

        // releases
        pinched.removeAll { ratios.getValue(it) > pinchOffRatio }

        // onset: only the closest finger, and only when nothing on this hand is pinched
        val newly = ArrayList<Finger>(1)
        if (pinched.isEmpty()) {
            val closest = ratios.minBy { it.value }
            val now = SystemClock.uptimeMillis()
            val last = lastOnsetByHand[handKey] ?: 0L
            if (closest.value < pinchOnRatio && now - last >= minRetriggerMs) {
                pinched += closest.key
                newly += closest.key
                lastOnsetByHand[handKey] = now
            }
        }
        return Result(pinched.toSet(), newly)
    }

    /** Forget hands that left the frame so nothing stays "stuck". */
    fun retain(visibleHandKeys: Set<String>) {
        pinchedByHand.keys.retainAll(visibleHandKeys)
        lastOnsetByHand.keys.retainAll(visibleHandKeys)
    }

    fun reset() {
        pinchedByHand.clear()
        lastOnsetByHand.clear()
    }
}

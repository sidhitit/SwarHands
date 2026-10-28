package com.demo.swarhands.gesture

import com.demo.swarhands.audio.SoundEngine
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/** Turns each frame of hand landmarks into notes + a drawable UI frame. Called on one thread only. */
class GestureEngine(private val sound: SoundEngine) {

    @Volatile var mode: PlayMode = PlayMode.PINCH

    private var lastMode = mode
    private val pinchDetector = PinchDetector()
    private val airKeyByHand = HashMap<String, Int>()

    fun process(result: HandLandmarkerResult, imgW: Int, imgH: Int, transform: ViewTransform): FrameUi {
        val currentMode = mode
        if (currentMode != lastMode) {
            pinchDetector.reset()
            airKeyByHand.clear()
            lastMode = currentMode
        }

        val hands = ArrayList<HandUi>(2)
        val activeKeys = HashSet<Int>()
        val toPlay = ArrayList<Swar>(2)
        val seen = HashSet<String>()

        val allLandmarks = result.landmarks()
        val handedness = result.handedness()
        for (i in allLandmarks.indices) {
            val lm = allLandmarks[i]
            if (lm.size < 21) continue
            val side = handedness.getOrNull(i)?.firstOrNull()?.categoryName() ?: "Right"
            val handKey = if (side in seen) "$side#$i" else side // two hands with the same label
            seen += handKey

            val points = lm.map { transform.map(it.x(), it.y()) }
            var pinched: Set<Finger> = emptySet()

            when (currentMode) {
                PlayMode.PINCH -> {
                    val r = pinchDetector.update(handKey, lm, imgW, imgH)
                    pinched = r.current
                    r.newlyPinched.forEach { toPlay += NoteMap.swarFor(side, it) }
                }
                PlayMode.AIR_KEYS -> {
                    val prev = airKeyByHand[handKey] ?: -1
                    val key = AirKeyboard.keyAt(points[8], transform.viewW, transform.viewH, prev)
                    if (key >= 0) {
                        activeKeys += key
                        if (key != prev) toPlay += Swar.entries[key]
                    }
                    airKeyByHand[handKey] = key
                }
            }
            hands += HandUi(side, points, pinched)
        }

        pinchDetector.retain(seen)
        airKeyByHand.keys.retainAll(seen)

        // play right here on the tracking thread for minimum latency
        toPlay.forEach { sound.play(it) }
        return FrameUi(hands, activeKeys, toPlay.lastOrNull())
    }
}

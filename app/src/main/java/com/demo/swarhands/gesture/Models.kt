package com.demo.swarhands.gesture

import android.graphics.Color
import android.graphics.PointF
import kotlin.math.max

/** Shuddh swar of one saptak plus taar Sa. */
enum class Swar(val latin: String, val hindi: String, val semitone: Int) {
    SA("Sa", "सा", 0),
    RE("Re", "रे", 2),
    GA("Ga", "ग", 4),
    MA("Ma", "म", 5),
    PA("Pa", "प", 7),
    DHA("Dha", "ध", 9),
    NI("Ni", "नि", 11),
    SA_HIGH("Sa'", "सां", 12);

    val color: Int get() = Color.HSVToColor(floatArrayOf(ordinal * 42f, 0.7f, 1f))
}

/** Fingers that can pinch against the thumb, with their MediaPipe tip landmark index. */
enum class Finger(val tipIndex: Int) {
    INDEX(8), MIDDLE(12), RING(16), PINKY(20)
}

enum class PlayMode { PINCH, AIR_KEYS }

object NoteMap {
    private val left = mapOf(
        Finger.INDEX to Swar.SA, Finger.MIDDLE to Swar.RE,
        Finger.RING to Swar.GA, Finger.PINKY to Swar.MA
    )
    private val right = mapOf(
        Finger.INDEX to Swar.PA, Finger.MIDDLE to Swar.DHA,
        Finger.RING to Swar.NI, Finger.PINKY to Swar.SA_HIGH
    )

    fun swarFor(side: String, finger: Finger): Swar =
        if (side == "Left") left.getValue(finger) else right.getValue(finger)
}

object HandSkeleton {
    const val THUMB_TIP = 4
    const val WRIST = 0
    const val MIDDLE_MCP = 9

    val BONES: List<Pair<Int, Int>> = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,           // thumb
        0 to 5, 5 to 6, 6 to 7, 7 to 8,           // index
        5 to 9, 9 to 10, 10 to 11, 11 to 12,      // middle
        9 to 13, 13 to 14, 14 to 15, 15 to 16,    // ring
        13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20 // pinky + palm
    )
}

/** Same math PreviewView uses for FILL_CENTER, so landmarks line up with the preview. */
class ViewTransform(val viewW: Int, val viewH: Int, imgW: Int, imgH: Int) {
    private val scale = max(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
    private val sw = imgW * scale
    private val sh = imgH * scale
    private val dx = (viewW - sw) / 2f
    private val dy = (viewH - sh) / 2f

    fun map(nx: Float, ny: Float): PointF = PointF(nx * sw + dx, ny * sh + dy)
}

/** Immutable data handed from the tracking thread to the overlay. */
data class HandUi(val side: String, val points: List<PointF>, val pinched: Set<Finger>)

data class FrameUi(
    val hands: List<HandUi>,
    val activeKeys: Set<Int>,
    val played: Swar?
)

package com.demo.swarhands.gesture

import android.graphics.PointF
import android.graphics.RectF

/** Eight virtual keys (Sa..Sa') laid out across the top of the screen. */
object AirKeyboard {
    const val KEY_COUNT = 8
    private const val TOP = 0.09f
    private const val BOTTOM = 0.32f
    private const val SIDE_MARGIN = 0.03f

    fun keyRect(index: Int, viewW: Int, viewH: Int): RectF {
        val left = viewW * SIDE_MARGIN
        val keyW = (viewW - 2 * left) / KEY_COUNT
        return RectF(left + index * keyW, viewH * TOP, left + (index + 1) * keyW, viewH * BOTTOM)
    }

    /** Returns the key under [p], or -1. Sticks to [previous] near its edges (hysteresis). */
    fun keyAt(p: PointF, viewW: Int, viewH: Int, previous: Int): Int {
        if (previous in 0 until KEY_COUNT) {
            val r = keyRect(previous, viewW, viewH)
            val mx = r.width() * 0.15f
            val my = r.height() * 0.10f
            if (p.x >= r.left - mx && p.x <= r.right + mx && p.y >= r.top - my && p.y <= r.bottom + my) {
                return previous
            }
        }
        for (i in 0 until KEY_COUNT) {
            if (keyRect(i, viewW, viewH).contains(p.x, p.y)) return i
        }
        return -1
    }
}

package com.demo.swarhands.games.bullseye

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/** Static weapon picture for the picker cards, drawn with the same painter as the game. */
class WeaponIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var weapon: Weapon = Weapon.BOW
        set(value) {
            field = value
            invalidate()
        }

    private val painter = WeaponPainter(resources.displayMetrics.density)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (weapon == Weapon.SLINGSHOT) {
            val s = min(w, h) * 0.36f
            val ax = w / 2f
            val ay = h * 0.38f
            painter.slingshot(c, ax, ay, s, ax + s * 0.15f, ay + s * 0.9f, 0.7f, loaded = true, over = false, now = 0L)
        } else {
            // diagonal, drawn back, aiming up-right
            val l = min(w, h) * 0.36f
            val k = 1f / sqrt(2f)
            val gx = w * 0.56f
            val gy = h * 0.44f
            painter.bow(c, gx, gy, k, -k, l, true, gx - k * l * 0.9f, gy + k * l * 0.9f, 0.8f)
        }
    }
}

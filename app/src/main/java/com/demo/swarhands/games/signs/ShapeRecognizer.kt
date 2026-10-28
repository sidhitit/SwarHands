package com.demo.swarhands.games.signs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recognises shapes drawn in the air with one finger, after the "$1 unistroke recognizer":
 * resample the stroke to [N] points, squash it into a unit box, centre it, then compare
 * point by point against templates, trying small rotations. Closed shapes get one template
 * per start corner and direction, so it doesn't matter where you start or which way you go.
 */
object ShapeRecognizer {

    private const val N = 64
    private const val MIN_SCORE = 0.78f
    private const val CORNER_TOL = 0.1f     // simplification tolerance, in shape sizes
    private const val SQUARE_CORNERS = 5
    private val MAX_TURN = (28.0 * PI / 180.0).toFloat()
    private val TURN_STEP = (2.0 * PI / 180.0).toFloat()
    private val PHI = (0.5 * (-1.0 + sqrt(5.0))).toFloat()
    private val HALF_DIAG = (0.5 * sqrt(2.0)).toFloat()

    private class Template(val shape: Sign, val pts: FloatArray)

    /** Shape outlines in a -1..1 box (y down), used for templates and for drawing the cards. */
    private val OUTLINES: Map<Sign, FloatArray> = mapOf(
        Sign.CIRCLE to FloatArray(66).also { a ->
            for (i in 0..32) {
                val t = -PI / 2 + i * 2 * PI / 32
                a[i * 2] = cos(t).toFloat()
                a[i * 2 + 1] = sin(t).toFloat()
            }
        },
        Sign.SQUARE to floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, -1f, -1f),
        Sign.TRIANGLE to floatArrayOf(0f, -1f, 1f, 0.85f, -1f, 0.85f, 0f, -1f),
        Sign.ZIGZAG to floatArrayOf(0.35f, -1f, -0.55f, 0.08f, 0.45f, -0.05f, -0.3f, 1f),
        Sign.STAR to FloatArray(12).also { a ->
            // pentagram: every second point of a pentagon
            val order = intArrayOf(0, 2, 4, 1, 3, 0)
            for (k in order.indices) {
                val t = -PI / 2 + order[k] * 2 * PI / 5
                a[k * 2] = cos(t).toFloat()
                a[k * 2 + 1] = sin(t).toFloat()
            }
        }
    )

    /** Closed outlines: how many corners can be a starting point (circle: 8 spots). */
    private val STARTS = mapOf(Sign.CIRCLE to 8, Sign.SQUARE to 4, Sign.TRIANGLE to 3, Sign.STAR to 5)

    private val TEMPLATES: List<Template> by lazy {
        val out = ArrayList<Template>()
        for ((shape, outline) in OUTLINES) {
            val corners = outline.size / 2 - 1 // last point repeats the first for closed shapes
            val starts = STARTS[shape]
            if (starts == null) {
                out += Template(shape, normalize(resample(outline)))
                out += Template(shape, normalize(resample(reversed(outline))))
            } else {
                for (k in 0 until starts) {
                    val shift = k * corners / starts
                    val rolled = roll(outline, shift)
                    out += Template(shape, normalize(resample(rolled)))
                    out += Template(shape, normalize(resample(reversed(rolled))))
                }
            }
        }
        out
    }

    /** Points of [shape]'s outline in a -1..1 box, for drawing. */
    fun outline(shape: Sign): FloatArray? = OUTLINES[shape]

    /** Warms up the templates off the UI thread. */
    fun prepare() {
        TEMPLATES.size
    }

    /**
     * [xy] = x0, y0, x1, y1 … of the stroke ([count] points). Returns the best shape, or null
     * when nothing matches well enough.
     */
    fun recognize(xy: FloatArray, count: Int): Sign? {
        if (count < 8) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            minX = min(minX, xy[i * 2]); maxX = max(maxX, xy[i * 2])
            minY = min(minY, xy[i * 2 + 1]); maxY = max(maxY, xy[i * 2 + 1])
        }
        val bw = maxX - minX
        val bh = maxY - minY
        if (min(bw, bh) < max(bw, bh) * 0.2f) return null // basically a line
        val cand = normalize(resample(xy.copyOf(count * 2)))
        var best: Sign? = null
        var bestD = Float.MAX_VALUE
        for (t in TEMPLATES) {
            val d = distanceAtBestAngle(cand, t.pts)
            if (d < bestD) {
                bestD = d
                best = t.shape
            }
        }
        val score = 1f - bestD / HALF_DIAG
        if (score < MIN_SCORE) return null
        // a wobbly square looks a lot like a circle: simplified to its main corners, a square
        // needs about 4 and a circle 7 or more
        if (best == Sign.CIRCLE || best == Sign.SQUARE) {
            best = if (corners(xy, count, max(bw, bh) * CORNER_TOL) <= SQUARE_CORNERS) Sign.SQUARE else Sign.CIRCLE
        }
        return best
    }

    /** Corner count after Douglas-Peucker simplification with tolerance [tol] (pixels). */
    private fun corners(xy: FloatArray, count: Int, tol: Float): Int {
        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true
        // closed strokes: split at the point farthest from the start so both halves simplify well
        var far = 0
        var farD = -1f
        for (i in 0 until count) {
            val d = hypot(xy[i * 2] - xy[0], xy[i * 2 + 1] - xy[1])
            if (d > farD) {
                farD = d
                far = i
            }
        }
        keep[far] = true
        simplify(xy, 0, far, tol, keep)
        simplify(xy, far, count - 1, tol, keep)
        var n = 0
        for (k in keep) if (k) n++
        // the end of a closed stroke lands back on its start: don't count that corner twice
        val closed = hypot(xy[(count - 1) * 2] - xy[0], xy[(count - 1) * 2 + 1] - xy[1]) < tol * 2f
        return if (closed) n - 1 else n
    }

    private fun simplify(xy: FloatArray, a: Int, b: Int, tol: Float, keep: BooleanArray) {
        if (b <= a + 1) return
        val ax = xy[a * 2]
        val ay = xy[a * 2 + 1]
        val bx = xy[b * 2]
        val by = xy[b * 2 + 1]
        val len = max(hypot(bx - ax, by - ay), 1e-3f)
        var worst = -1
        var worstD = tol
        for (i in a + 1 until b) {
            val d = abs((bx - ax) * (ay - xy[i * 2 + 1]) - (ax - xy[i * 2]) * (by - ay)) / len
            if (d > worstD) {
                worstD = d
                worst = i
            }
        }
        if (worst < 0) return
        keep[worst] = true
        simplify(xy, a, worst, tol, keep)
        simplify(xy, worst, b, tol, keep)
    }

    // ---------------------------------------------------------------- $1 steps

    private fun resample(src: FloatArray, n: Int = N): FloatArray {
        val m = src.size / 2
        var total = 0f
        for (i in 1 until m) total += hypot(src[i * 2] - src[i * 2 - 2], src[i * 2 + 1] - src[i * 2 - 1])
        val step = total / (n - 1)
        val out = FloatArray(n * 2)
        out[0] = src[0]
        out[1] = src[1]
        var k = 1
        var acc = 0f
        var px = src[0]
        var py = src[1]
        var i = 1
        while (i < m && k < n) {
            val qx = src[i * 2]
            val qy = src[i * 2 + 1]
            val d = hypot(qx - px, qy - py)
            if (step > 0f && acc + d >= step) {
                val f = (step - acc) / d
                px += f * (qx - px)
                py += f * (qy - py)
                out[k * 2] = px
                out[k * 2 + 1] = py
                k++
                acc = 0f
            } else {
                acc += d
                px = qx
                py = qy
                i++
            }
        }
        while (k < n) { // rounding: pad with the last point
            out[k * 2] = src[src.size - 2]
            out[k * 2 + 1] = src[src.size - 1]
            k++
        }
        return out
    }

    /** Squash into a unit box and move the centroid to the origin. */
    private fun normalize(p: FloatArray): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until N) {
            minX = min(minX, p[i * 2]); maxX = max(maxX, p[i * 2])
            minY = min(minY, p[i * 2 + 1]); maxY = max(maxY, p[i * 2 + 1])
        }
        val sx = max(maxX - minX, 1e-3f)
        val sy = max(maxY - minY, 1e-3f)
        var cx = 0f
        var cy = 0f
        for (i in 0 until N) {
            p[i * 2] = (p[i * 2] - minX) / sx
            p[i * 2 + 1] = (p[i * 2 + 1] - minY) / sy
            cx += p[i * 2]
            cy += p[i * 2 + 1]
        }
        cx /= N
        cy /= N
        for (i in 0 until N) {
            p[i * 2] -= cx
            p[i * 2 + 1] -= cy
        }
        return p
    }

    private fun distanceAtBestAngle(c: FloatArray, t: FloatArray): Float {
        var a = -MAX_TURN
        var b = MAX_TURN
        var x1 = PHI * a + (1 - PHI) * b
        var f1 = distanceAt(c, t, x1)
        var x2 = (1 - PHI) * a + PHI * b
        var f2 = distanceAt(c, t, x2)
        while (abs(b - a) > TURN_STEP) {
            if (f1 < f2) {
                b = x2; x2 = x1; f2 = f1
                x1 = PHI * a + (1 - PHI) * b
                f1 = distanceAt(c, t, x1)
            } else {
                a = x1; x1 = x2; f1 = f2
                x2 = (1 - PHI) * a + PHI * b
                f2 = distanceAt(c, t, x2)
            }
        }
        return min(f1, f2)
    }

    /** Mean point distance with the candidate rotated by [angle] about the origin. */
    private fun distanceAt(c: FloatArray, t: FloatArray, angle: Float): Float {
        val cs = cos(angle)
        val sn = sin(angle)
        var d = 0f
        for (i in 0 until N) {
            val x = c[i * 2] * cs - c[i * 2 + 1] * sn
            val y = c[i * 2] * sn + c[i * 2 + 1] * cs
            d += hypot(x - t[i * 2], y - t[i * 2 + 1])
        }
        return d / N
    }

    private fun reversed(p: FloatArray): FloatArray {
        val n = p.size / 2
        return FloatArray(p.size) { i -> p[(n - 1 - i / 2) * 2 + i % 2] }
    }

    /** Closed outline started from corner [shift] (the closing point is rebuilt). */
    private fun roll(p: FloatArray, shift: Int): FloatArray {
        val corners = p.size / 2 - 1
        val out = FloatArray(p.size)
        for (k in 0..corners) {
            val src = (k + shift) % corners
            out[k * 2] = p[src * 2]
            out[k * 2 + 1] = p[src * 2 + 1]
        }
        return out
    }
}

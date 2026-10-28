package com.demo.swarhands.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class Instrument(val label: String) {
    PIANO("Piano"),
    HARMONIUM("Harmonium"),
    BANSURI("Bansuri")
}

/** Generates note waveforms in code, so the app needs no audio files at all. */
object ToneSynth {
    const val SAMPLE_RATE = 44100
    private const val NYQUIST_LIMIT = SAMPLE_RATE / 2.2

    fun render(freq: Double, instrument: Instrument): ShortArray {
        val raw = when (instrument) {
            Instrument.PIANO -> piano(freq)
            Instrument.HARMONIUM -> harmonium(freq)
            Instrument.BANSURI -> bansuri(freq)
        }
        return finish(raw)
    }

    private fun piano(freq: Double): FloatArray {
        val n = (1.8 * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        for (h in 1..8) {
            val f = freq * h * (1 + 0.0004 * h * h) // slight string inharmonicity
            if (f > NYQUIST_LIMIT) break
            val amp = 1.0 / h.toDouble().pow(1.4)
            val decayRate = 1.8 + 0.9 * h // higher harmonics fade faster
            val w = 2 * PI * f / SAMPLE_RATE
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                out[i] += (amp * exp(-t * decayRate) * sin(w * i)).toFloat()
            }
        }
        applyEnvelope(out, attackSec = 0.004, releaseSec = 0.05)
        return out
    }

    private fun harmonium(freq: Double): FloatArray {
        val n = (1.6 * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val detune = 1.0025 // second reed slightly sharp -> gentle beating
        for (h in 1..12) {
            val f = freq * h
            if (f * detune > NYQUIST_LIMIT) break
            val amp = (1.0 / h) * (if (h % 2 == 0) 0.7 else 1.0)
            val w1 = 2 * PI * f / SAMPLE_RATE
            val w2 = w1 * detune
            for (i in 0 until n) {
                out[i] += (amp * 0.5 * (sin(w1 * i) + sin(w2 * i))).toFloat()
            }
        }
        applyEnvelope(out, attackSec = 0.04, releaseSec = 0.25)
        return out
    }

    private fun bansuri(freq: Double): FloatArray {
        val n = (1.5 * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(42)
        var phase = 0.0
        var noise = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            // vibrato fades in after 0.25 s, like a real flute player
            val vibDepth = 0.006 * min(1.0, max(0.0, (t - 0.25) / 0.3))
            val f = freq * (1 + vibDepth * sin(2 * PI * 5.5 * t))
            phase += 2 * PI * f / SAMPLE_RATE
            noise += 0.08 * (rnd.nextGaussian() - noise) // low-passed breath
            var s = sin(phase) + 0.18 * sin(2 * phase)
            if (3 * freq < NYQUIST_LIMIT) s += 0.06 * sin(3 * phase)
            out[i] = (s + 0.25 * noise).toFloat()
        }
        applyEnvelope(out, attackSec = 0.08, releaseSec = 0.3)
        return out
    }

    private fun applyEnvelope(buf: FloatArray, attackSec: Double, releaseSec: Double) {
        val a = max(1, (attackSec * SAMPLE_RATE).toInt())
        val r = max(1, (releaseSec * SAMPLE_RATE).toInt())
        val n = buf.size
        for (i in 0 until min(a, n)) buf[i] *= i.toFloat() / a
        for (i in 0 until min(r, n)) buf[n - 1 - i] *= i.toFloat() / r
    }

    /** Normalizes to a common peak, adds a click-free tail and converts to 16-bit PCM. */
    private fun finish(buf: FloatArray): ShortArray {
        var peak = 1e-6f
        for (v in buf) peak = max(peak, abs(v))
        val gain = 0.85f / peak
        val fade = (0.01 * SAMPLE_RATE).toInt()
        val n = buf.size
        return ShortArray(n) { i ->
            var v = buf[i] * gain
            val fromEnd = n - 1 - i
            if (fromEnd < fade) v *= fromEnd.toFloat() / fade
            (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}

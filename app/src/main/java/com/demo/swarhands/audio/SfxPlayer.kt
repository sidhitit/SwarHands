package com.demo.swarhands.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Sfx {
    TICK, BEAT, SHOOT, ROUND_WIN, ROUND_LOSE, DRAW, GAME_WIN, GAME_LOSE,
    // Bullseye
    STRETCH, CREAK, SNAP, TWANG, THUNK, KNOCK, BULLSEYE, MISS, OVERPOWER
}

/** Game sound effects, synthesized once in code and cached (no audio assets). */
class SfxPlayer(context: Context) {

    private val cacheDir = context.applicationContext.cacheDir
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = ConcurrentHashMap<Sfx, Int>()
    private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val loader = Executors.newSingleThreadExecutor()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        loader.execute {
            for (s in Sfx.entries) {
                val file = File(cacheDir, "sfx_v1_${s.name}.wav")
                if (!file.exists()) WavWriter.write(file, synth(s), SAMPLE_RATE)
                ids[s] = pool.load(file.absolutePath, 1)
            }
        }
    }

    /** [rate] shifts pitch and speed together (0.5 – 2.0), e.g. a stretch that rises as you pull. */
    fun play(s: Sfx, volume: Float = 0.8f, rate: Float = 1f) {
        val id = ids[s] ?: return
        if (id in ready) pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() {
        loader.shutdownNow()
        pool.release()
    }

    private class Note(val start: Double, val freq: Double, val dur: Double)

    private companion object {
        const val SAMPLE_RATE = ToneSynth.SAMPLE_RATE
        val SOFT = doubleArrayOf(1.0, 0.15)
        val BRIGHT = doubleArrayOf(1.0, 0.35, 0.12)
        val BUZZY = doubleArrayOf(1.0, 0.5, 0.33, 0.25, 0.2, 0.16)

        fun synth(s: Sfx): ShortArray = when (s) {
            Sfx.TICK -> render(listOf(Note(0.0, 1318.5, 0.08)), SOFT, 30.0)
            Sfx.BEAT -> render(listOf(Note(0.0, 880.0, 0.11)), BRIGHT, 20.0)
            Sfx.SHOOT -> render(listOf(Note(0.0, 1046.5, 0.25), Note(0.0, 1568.0, 0.25)), BRIGHT, 9.0)
            Sfx.ROUND_WIN -> render(listOf(Note(0.0, 783.99, 0.12), Note(0.09, 1046.5, 0.3)), BRIGHT, 8.0)
            Sfx.ROUND_LOSE -> render(listOf(Note(0.0, 392.0, 0.15), Note(0.13, 293.66, 0.35)), BUZZY, 7.0)
            Sfx.DRAW -> render(listOf(Note(0.0, 659.25, 0.12), Note(0.14, 659.25, 0.14)), SOFT, 14.0)
            Sfx.GAME_WIN -> render(
                listOf(
                    Note(0.0, 523.25, 0.18), Note(0.1, 659.25, 0.18), Note(0.2, 783.99, 0.18),
                    Note(0.3, 1046.5, 0.7), Note(0.3, 1318.5, 0.7)
                ), BRIGHT, 4.0
            )
            Sfx.GAME_LOSE -> render(
                listOf(Note(0.0, 392.0, 0.28), Note(0.24, 311.13, 0.28), Note(0.48, 261.63, 0.8)),
                BUZZY, 3.5
            )
            Sfx.STRETCH -> stretch()
            Sfx.CREAK -> creak()
            Sfx.SNAP -> snap()
            Sfx.TWANG -> twang()
            Sfx.THUNK -> thunk()
            Sfx.KNOCK -> knock()
            Sfx.BULLSEYE -> render(
                listOf(Note(0.0, 1046.5, 0.3), Note(0.07, 1568.0, 0.35), Note(0.14, 2093.0, 0.55)),
                BRIGHT, 5.0
            )
            Sfx.MISS -> miss()
            Sfx.OVERPOWER -> render(listOf(Note(0.0, 233.08, 0.09), Note(0.12, 233.08, 0.12)), BUZZY, 9.0)
        }

        private fun samples(sec: Double) = (sec * SAMPLE_RATE).toInt()

        /** Rubber band squeak: a rising tone chopped by a rough stick-slip buzz. */
        fun stretch(): ShortArray {
            val n = samples(0.16)
            val buf = FloatArray(n)
            val rnd = Random(7)
            var phase = 0.0
            var lp = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * (420.0 + 1600.0 * t) / SAMPLE_RATE
                val grain = if (sin(2 * PI * 55 * t) > 0) 1.0 else 0.35
                lp += 0.25 * (rnd.nextGaussian() - lp)
                val env = min(1.0, t / 0.015) * exp(-t * 9)
                buf[i] = ((sin(phase) + 0.35 * sin(2 * phase) + 0.3 * lp) * grain * env).toFloat()
            }
            return pcm(buf, 0.7f)
        }

        /** Wooden bow creak: a burst of tiny irregular clicks ringing a few wood modes. */
        fun creak(): ShortArray {
            val n = samples(0.28)
            val buf = FloatArray(n)
            val rnd = Random(11)
            val modes = doubleArrayOf(240.0, 585.0, 1130.0)
            val amps = doubleArrayOf(1.0, 0.6, 0.3)
            val clickLen = samples(0.03)
            var at = 0.0
            while (at < 0.24) {
                val s0 = samples(at)
                val amp = 0.5 + 0.5 * rnd.nextDouble()
                for (j in 0 until clickLen) {
                    val idx = s0 + j
                    if (idx >= n) break
                    val tt = j.toDouble() / SAMPLE_RATE
                    var v = 0.0
                    for (k in modes.indices) v += amps[k] * sin(2 * PI * modes[k] * tt)
                    buf[idx] += (amp * v * exp(-tt * 160)).toFloat()
                }
                at += 0.012 + 0.018 * rnd.nextDouble()
            }
            for (i in 0 until n) buf[i] *= sin(PI * i / n).toFloat() // swell in and out
            return pcm(buf, 0.75f)
        }

        /** Slingshot release: a falling rubber "thwip", a sharp crack and an air whoosh. */
        fun snap(): ShortArray {
            val n = samples(0.42)
            val buf = FloatArray(n)
            val rnd = Random(3)
            var phase = 0.0
            var lp = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * (180.0 + 920.0 * exp(-t * 35)) / SAMPLE_RATE
                val thwip = sin(phase) * exp(-t * 22)
                val noise = rnd.nextGaussian()
                val crack = if (t < 0.012) noise * (1 - t / 0.012) else 0.0
                lp += 0.12 * (noise - lp)
                val swell = sin(PI * t / 0.42)
                buf[i] = (0.9 * thwip + 0.7 * crack + 1.4 * lp * swell * swell).toFloat()
            }
            return pcm(buf, 0.85f)
        }

        /** Bow release: a plucked string (Karplus-Strong), a low thump and the arrow's whoosh. */
        fun twang(): ShortArray {
            val n = samples(0.7)
            val buf = FloatArray(n)
            val rnd = Random(5)
            val period = (SAMPLE_RATE / 110.0).toInt()
            val line = FloatArray(period) { rnd.nextFloat() * 2f - 1f }
            var p = 0
            var lp = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val cur = line[p]
                line[p] = 0.996f * 0.5f * (cur + line[(p + 1) % period])
                p = (p + 1) % period
                val thump = sin(2 * PI * 70 * t) * exp(-t * 30)
                lp += 0.1 * (rnd.nextGaussian() - lp)
                val swell = sin(PI * min(1.0, t / 0.45))
                buf[i] = (cur + 0.8 * thump + 1.2 * lp * swell * swell).toFloat()
            }
            return pcm(buf, 0.85f)
        }

        /** Arrow hitting the straw target: a deep thunk plus the shaft quivering. */
        fun thunk(): ShortArray {
            val n = samples(0.38)
            val buf = FloatArray(n)
            val rnd = Random(9)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * (70.0 + 150.0 * exp(-t * 40)) / SAMPLE_RATE
                val body = sin(phase) * exp(-t * 18)
                val click = rnd.nextGaussian() * exp(-t * 400)
                val quiver = sin(2 * PI * 210 * t) * (0.5 + 0.5 * sin(2 * PI * 22 * t)) * exp(-t * 9)
                buf[i] = (body + 0.5 * click + 0.35 * quiver).toFloat()
            }
            return pcm(buf, 0.9f)
        }

        /** Stone hitting wood: a few bright wooden modes and a crack. */
        fun knock(): ShortArray {
            val n = samples(0.3)
            val buf = FloatArray(n)
            val rnd = Random(13)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val wood = sin(2 * PI * 480 * t) * exp(-t * 28) +
                    0.5 * sin(2 * PI * 1210 * t) * exp(-t * 45) +
                    0.3 * sin(2 * PI * 2050 * t) * exp(-t * 70)
                val low = sin(2 * PI * 120 * t) * exp(-t * 25)
                val crack = rnd.nextGaussian() * exp(-t * 300)
                buf[i] = (wood + 0.6 * low + 0.6 * crack).toFloat()
            }
            return pcm(buf, 0.9f)
        }

        /** Missed shot landing in the grass: a soft low thud. */
        fun miss(): ShortArray {
            val n = samples(0.3)
            val buf = FloatArray(n)
            val rnd = Random(17)
            var phase = 0.0
            var lp = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * (55.0 + 60.0 * exp(-t * 30)) / SAMPLE_RATE
                lp += 0.15 * (rnd.nextGaussian() - lp)
                buf[i] = (sin(phase) * exp(-t * 14) + 0.6 * lp * exp(-t * 20)).toFloat()
            }
            return pcm(buf, 0.7f)
        }

        /** Normalizes to [peakTarget] with a short fade at the end so nothing clicks. */
        fun pcm(buf: FloatArray, peakTarget: Float): ShortArray {
            var peak = 1e-6f
            for (v in buf) peak = max(peak, abs(v))
            val gain = peakTarget / peak
            val n = buf.size
            val fade = samples(0.005)
            return ShortArray(n) { i ->
                var v = buf[i] * gain
                val fromEnd = n - 1 - i
                if (fromEnd < fade) v *= fromEnd.toFloat() / fade
                (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        fun render(notes: List<Note>, harmonics: DoubleArray, decay: Double): ShortArray {
            val total = notes.maxOf { it.start + it.dur } + 0.02
            val n = (total * SAMPLE_RATE).toInt()
            val buf = FloatArray(n)
            val attack = (0.004 * SAMPLE_RATE).toInt()
            val release = (0.015 * SAMPLE_RATE).toInt()
            for (note in notes) {
                val s0 = (note.start * SAMPLE_RATE).toInt()
                val len = (note.dur * SAMPLE_RATE).toInt()
                for (i in 0 until len) {
                    val idx = s0 + i
                    if (idx >= n) break
                    val t = i.toDouble() / SAMPLE_RATE
                    var v = 0.0
                    for (h in harmonics.indices) {
                        val f = note.freq * (h + 1)
                        if (f > SAMPLE_RATE / 2.2) break
                        v += harmonics[h] * sin(2 * PI * f * t)
                    }
                    var env = exp(-t * decay)
                    if (i < attack) env *= i.toDouble() / attack
                    if (len - i < release) env *= (len - i).toDouble() / release
                    buf[idx] += (v * env).toFloat()
                }
            }
            var peak = 1e-6f
            for (v in buf) peak = max(peak, abs(v))
            val gain = 0.8f / peak
            return ShortArray(n) { (buf[it] * gain * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}

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
    STRETCH, CREAK, SNAP, TWANG, THUNK, KNOCK, BULLSEYE, MISS, OVERPOWER,
    // Gully Cricket
    BAT, STUMPS, CHEER, GROAN, GLASS, BOUNCE, SWISH
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
            Sfx.BAT -> bat()
            Sfx.STUMPS -> stumps()
            Sfx.CHEER -> crowd(cheer = true)
            Sfx.GROAN -> crowd(cheer = false)
            Sfx.GLASS -> glass()
            Sfx.BOUNCE -> bounce()
            Sfx.SWISH -> swish()
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

        /** Willow on leather: a hard click and a short, bright wooden ring. */
        fun bat(): ShortArray {
            val n = samples(0.32)
            val buf = FloatArray(n)
            val rnd = Random(21)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val click = rnd.nextGaussian() * exp(-t * 900)
                val ring = sin(2 * PI * 1180 * t) * exp(-t * 38) +
                    0.6 * sin(2 * PI * 2390 * t) * exp(-t * 60) +
                    0.35 * sin(2 * PI * 3720 * t) * exp(-t * 90)
                val body = sin(2 * PI * 210 * t) * exp(-t * 30)
                buf[i] = (1.2 * click + ring + 0.7 * body).toFloat()
            }
            return pcm(buf, 0.95f)
        }

        /** Stumps flattened: three wooden knocks tumbling over each other, then the bails rattling. */
        fun stumps(): ShortArray {
            val n = samples(0.6)
            val buf = FloatArray(n)
            val rnd = Random(23)
            val hits = doubleArrayOf(0.0, 0.035, 0.08, 0.16, 0.24, 0.31)
            val pitch = doubleArrayOf(520.0, 610.0, 470.0, 1350.0, 1500.0, 1420.0)
            val amp = doubleArrayOf(1.0, 0.9, 0.8, 0.45, 0.35, 0.25)
            for (k in hits.indices) {
                val s0 = samples(hits[k])
                for (j in 0 until samples(0.18)) {
                    val idx = s0 + j
                    if (idx >= n) break
                    val t = j.toDouble() / SAMPLE_RATE
                    val v = sin(2 * PI * pitch[k] * t) * exp(-t * 40) +
                        0.5 * sin(2 * PI * pitch[k] * 2.7 * t) * exp(-t * 70) +
                        0.6 * rnd.nextGaussian() * exp(-t * 350)
                    buf[idx] += (amp[k] * v).toFloat()
                }
            }
            return pcm(buf, 0.95f)
        }

        /** A small crowd: band-limited roar plus many voices sliding up (cheer) or down (groan). */
        fun crowd(cheer: Boolean): ShortArray {
            val dur = if (cheer) 1.9 else 1.3
            val n = samples(dur)
            val buf = FloatArray(n)
            val rnd = Random(if (cheer) 31 else 37)
            var lp1 = 0.0
            var lp2 = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val x = rnd.nextGaussian()
                lp1 += 0.18 * (x - lp1)
                lp2 += 0.03 * (x - lp2)
                val env = min(1.0, t / 0.12) * exp(-t * (if (cheer) 1.3 else 2.0))
                buf[i] = ((lp1 - lp2) * 2.2 * env).toFloat() // band-pass: the roar
            }
            val voices = if (cheer) 14 else 10
            for (v in 0 until voices) {
                val f0 = (if (cheer) 330.0 else 260.0) + rnd.nextDouble() * 320.0
                val start = rnd.nextDouble() * 0.25
                val len = dur * (0.55 + 0.4 * rnd.nextDouble())
                val vib = 4.0 + rnd.nextDouble() * 3.0
                var ph = 0.0
                for (j in 0 until samples(len)) {
                    val idx = samples(start) + j
                    if (idx >= n) break
                    val t = j.toDouble() / SAMPLE_RATE
                    val slide = if (cheer) 1.0 + 0.35 * min(1.0, t / 0.3) else 1.15 - 0.4 * min(1.0, t / len)
                    ph += 2 * PI * f0 * slide * (1 + 0.012 * sin(2 * PI * vib * t)) / SAMPLE_RATE
                    val env = min(1.0, t / 0.08) * (1 - t / len)
                    val tone = sin(ph) + 0.45 * sin(2 * ph) + 0.25 * sin(3 * ph) // "aah"
                    buf[idx] += (0.11 * tone * env).toFloat()
                }
            }
            return pcm(buf, if (cheer) 0.85f else 0.7f)
        }

        /** Window smash: a crack, then a shower of high glassy pings. */
        fun glass(): ShortArray {
            val n = samples(0.75)
            val buf = FloatArray(n)
            val rnd = Random(41)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                buf[i] = (rnd.nextGaussian() * exp(-t * 60) * 0.9).toFloat()
            }
            for (k in 0 until 26) {
                val s0 = samples(rnd.nextDouble() * 0.45)
                val f = 2200.0 + rnd.nextDouble() * 4200.0
                val a = 0.25 + 0.5 * rnd.nextDouble()
                for (j in 0 until samples(0.2)) {
                    val idx = s0 + j
                    if (idx >= n) break
                    val t = j.toDouble() / SAMPLE_RATE
                    buf[idx] += (a * sin(2 * PI * f * t) * exp(-t * 28)).toFloat()
                }
            }
            return pcm(buf, 0.85f)
        }

        /** Ball pitching on the hard gully road. */
        fun bounce(): ShortArray {
            val n = samples(0.14)
            val buf = FloatArray(n)
            val rnd = Random(43)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * (150.0 + 260.0 * exp(-t * 60)) / SAMPLE_RATE
                buf[i] = (sin(phase) * exp(-t * 35) + 0.5 * rnd.nextGaussian() * exp(-t * 500)).toFloat()
            }
            return pcm(buf, 0.75f)
        }

        /** The bat cutting the air. */
        fun swish(): ShortArray {
            val n = samples(0.26)
            val buf = FloatArray(n)
            val rnd = Random(47)
            var lp = 0.0
            var prev = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val k = 0.05 + 0.4 * sin(PI * t / 0.26) // filter opens and closes = "whoosh"
                lp += k * (rnd.nextGaussian() - lp)
                val hp = lp - prev
                prev = lp
                val env = sin(PI * t / 0.26)
                buf[i] = ((lp * 0.6 + hp * 1.5) * env * env).toFloat()
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

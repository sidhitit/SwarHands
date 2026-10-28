package com.demo.swarhands.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

enum class Sfx { TICK, BEAT, SHOOT, ROUND_WIN, ROUND_LOSE, DRAW, GAME_WIN, GAME_LOSE }

/** Game sound effects, synthesized once in code and cached (no audio assets). */
class SfxPlayer(context: Context) {

    private val cacheDir = context.applicationContext.cacheDir
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
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

    fun play(s: Sfx, volume: Float = 0.8f) {
        val id = ids[s] ?: return
        if (id in ready) pool.play(id, volume, volume, 1, 0, 1f)
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

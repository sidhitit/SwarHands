package com.demo.swarhands.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.demo.swarhands.gesture.Swar
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Low-latency polyphonic playback via SoundPool.
 * Tones are synthesized once, cached as WAV files in cacheDir, then loaded into SoundPool.
 */
class SoundEngine(context: Context) {

    private val cacheDir = context.applicationContext.cacheDir
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .build()

    private val soundIds = ConcurrentHashMap<Swar, Int>()
    private val readyIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val loader = Executors.newSingleThreadExecutor()

    @Volatile private var generation = 0

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) readyIds.add(sampleId)
        }
    }

    /** Builds (or reuses cached) tones for all 8 swar. [onDone] runs on a background thread. */
    fun load(instrument: Instrument, saHz: Double, onDone: () -> Unit) {
        val gen = ++generation
        loader.execute {
            val old = soundIds.values.toList()
            soundIds.clear()
            old.forEach { id ->
                readyIds.remove(id)
                pool.unload(id)
            }
            for (swar in Swar.entries) {
                if (gen != generation) return@execute // a newer request superseded this one
                val freq = saHz * 2.0.pow(swar.semitone / 12.0)
                val file = File(cacheDir, "tone_${instrument.name}_${(freq * 100).roundToInt()}.wav")
                if (!file.exists()) {
                    WavWriter.write(file, ToneSynth.render(freq, instrument), ToneSynth.SAMPLE_RATE)
                }
                soundIds[swar] = pool.load(file.absolutePath, 1)
            }
            if (gen == generation) onDone()
        }
    }

    fun play(swar: Swar, volume: Float = 0.9f) {
        val id = soundIds[swar] ?: return
        if (id !in readyIds) return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        generation++
        loader.shutdownNow()
        pool.release()
    }
}

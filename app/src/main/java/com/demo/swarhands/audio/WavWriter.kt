package com.demo.swarhands.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal mono 16-bit PCM WAV encoder. */
object WavWriter {
    fun write(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                 // PCM chunk size
        buf.putShort(1)                // format = PCM
        buf.putShort(1)                // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)     // byte rate
        buf.putShort(2)                // block align
        buf.putShort(16)               // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)

        // write to a temp file first so SoundPool never reads a half-written file
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(buf.array())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

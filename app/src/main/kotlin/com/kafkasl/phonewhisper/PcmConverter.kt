package com.kafkasl.phonewhisper

/** Converts little-endian signed 16-bit PCM bytes to float samples in [-1, 1). */
object PcmConverter {
    fun toFloatSamples(pcm: ByteArray, byteCount: Int = pcm.size): FloatArray {
        val samples = FloatArray(byteCount / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return samples
    }
}

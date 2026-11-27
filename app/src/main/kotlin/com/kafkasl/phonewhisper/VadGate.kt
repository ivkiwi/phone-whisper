package com.kafkasl.phonewhisper

/**
 * Gates audio in front of a streaming recognizer with a voice activity detector, so the
 * recognizer only hears speech: silence and background noise never reach it (no
 * hallucinated words), and the end of each speech region finalizes the segment.
 *
 * Audio is cut into fixed windows for the [detector]. While silent, the last
 * [preRollWindows] windows are kept so the start of a word that the detector confirms
 * only after a short delay is not lost. Pure logic, no Android or native dependencies.
 */
class VadGate(
    private val windowSize: Int,
    private val preRollWindows: Int,
    private val detector: (FloatArray) -> Boolean,
) {
    sealed interface Event {
        /** Speech audio to feed to the recognizer. */
        class Audio(val samples: FloatArray) : Event
        /** The speech region ended: finalize the current segment. */
        object SpeechEnd : Event
    }

    private val pending = FloatArray(windowSize)
    private var pendingLen = 0
    private val preRoll = ArrayDeque<FloatArray>()

    var inSpeech = false
        private set

    /** Feed captured samples; resulting events are appended to [out] in order. */
    fun accept(samples: FloatArray, out: MutableList<Event>) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(windowSize - pendingLen, samples.size - i)
            System.arraycopy(samples, i, pending, pendingLen, n)
            pendingLen += n
            i += n
            if (pendingLen == windowSize) {
                pendingLen = 0
                onWindow(pending.copyOf(), out)
            }
        }
    }

    private fun onWindow(window: FloatArray, out: MutableList<Event>) {
        val speech = detector(window)
        if (speech) {
            if (!inSpeech) {
                inSpeech = true
                preRoll.forEach { out += Event.Audio(it) }
                preRoll.clear()
            }
            out += Event.Audio(window)
        } else {
            if (inSpeech) {
                inSpeech = false
                out += Event.SpeechEnd
            }
            preRoll.addLast(window)
            while (preRoll.size > preRollWindows) preRoll.removeFirst()
        }
    }

    /** Start over (new utterance or after a forced cut). */
    fun reset() {
        inSpeech = false
        pendingLen = 0
        preRoll.clear()
    }
}

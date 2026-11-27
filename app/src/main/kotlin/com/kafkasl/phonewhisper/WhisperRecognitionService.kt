package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Speech recognition for apps using the SpeechRecognizer API (no window), backed by the
 * dictation engine. Phone Whisper is in the background while an app listens, so the
 * microphone is opened here on behalf of the calling app (its attribution source, Android
 * 12+), and the audio is streamed into the engine. Uses the model and mode of the voice
 * button chosen for "Voice input for other apps". Partial results are sent while speaking
 * (streaming models, or offline models phrase by phrase on a hold-to-talk button); the
 * recording ends after a pause, on stopListening(), or on a no-speech timeout.
 */
class WhisperRecognitionService : RecognitionService() {

    private val main = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    @Volatile private var record: AudioRecord? = null

    private val committed = StringBuilder()
    private var partial = ""
    private var live = false
    private var speechSeen = false
    private var quietSince = 0L
    private var startedAt = 0L
    private var silenceLimitMs = RecognizeActivity.DEFAULT_SILENCE_MS
    private var noSpeechTimeoutMs = NO_SPEECH_TIMEOUT_MS
    private var sentEnd = false

    private val listener = object : DictationEngine.Listener {
        override fun onStateChanged(state: DictationEngine.State) {
            if (state == DictationEngine.State.RECORDING) live = DictationEngine.isLive
        }

        override fun onLivePartial(text: String) {
            partial = text
            sendPartial()
        }

        override fun onLiveSegment(text: String, forced: Boolean) {
            if (text.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text.trim())
            }
            partial = ""
            sendPartial()
        }

        override fun onResult(text: String, notice: DictationEngine.Notice) {
            val raw = text.ifBlank { committed.toString() }.trim()
            if (raw.isEmpty()) { fail(SpeechRecognizer.ERROR_NO_MATCH); return }
            if (live && DictationEngine.isCleanupEnabled()) {
                DictationEngine.cleanup(raw) { cleaned, _ -> main.post { deliver(cleaned.trim().ifBlank { raw }) } }
            } else {
                deliver(raw)
            }
        }

        override fun onError(message: String) {
            ImeLog.add("recognizer: error $message")
            fail(if (message.startsWith("No speech")) SpeechRecognizer.ERROR_NO_MATCH else SpeechRecognizer.ERROR_CLIENT)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DictationEngine.attach(this)
        ImeLog.init(this)
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (callback != null || DictationEngine.state != DictationEngine.State.IDLE) {
            safe { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ImeLog.add("recognizer: Phone Whisper has no microphone permission")
            safe { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        val button = RecognizeActivity.popupButton(this)
        if (!VoiceButtons.isReady(this, button)) {
            ImeLog.add("recognizer: no model for the chosen voice button")
            safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        callback = listener
        committed.setLength(0)
        partial = ""
        live = false
        speechSeen = false
        quietSince = 0
        sentEnd = false
        startedAt = System.currentTimeMillis()
        val extraSilence = recognizerIntent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
        silenceLimitMs = when {
            extraSilence > 0 -> extraSilence
            RecognizeActivity.popupSilenceMs(this) > 0 -> RecognizeActivity.popupSilenceMs(this)
            else -> RecognizeActivity.DEFAULT_SILENCE_MS // without a window the caller relies on end-of-speech
        }
        ImeLog.add("recognizer: start for uid ${listener.callingUid}")

        val rec = openMic(listener) ?: run {
            finishWith { it.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        val role = if (button.pushToTalk) DictationEngine.Role.PTT else DictationEngine.Role.TAP
        val model = button.model.takeIf { it.isNotBlank() }?.let { VoiceButtons.resolveModel(this, button) }
        if (DictationEngine.startExternal(this.listener, cleanup = true, live = true, role = role, model = model)
            != DictationEngine.StartResult.STARTED) {
            rec.release()
            finishWith { it.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        record = rec
        safe { listener.readyForSpeech(Bundle()) }
        pump(rec)
    }

    override fun onStopListening(listener: Callback) {
        stopRecording()
    }

    override fun onCancel(listener: Callback) {
        record = null
        DictationEngine.cancel(this.listener)
        callback = null
    }

    override fun onDestroy() {
        record = null
        DictationEngine.cancel(listener)
        callback = null
        super.onDestroy()
    }

    /** Open the microphone attributed to the calling app, so its foreground state allows recording. */
    private fun openMic(cb: Callback): AudioRecord? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 2))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ctx: Context = createContext(
                    ContextParams.Builder().setNextAttributionSource(cb.callingAttributionSource).build()
                )
                builder.setContext(ctx)
            }
            val rec = builder.build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
            rec.startRecording()
            rec
        } catch (e: Exception) {
            Log.e(TAG, "microphone failed", e)
            ImeLog.add("recognizer: microphone failed: ${e.message}")
            null
        }
    }

    /** Read the microphone on a worker thread and hand chunks to the engine on the main thread. */
    private fun pump(rec: AudioRecord) {
        thread(name = "WhisperRecognitionMic") {
            val buf = ByteArray(CHUNK_BYTES)
            while (record === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val pcm = buf.copyOf(n)
                val rms = rms(pcm)
                main.post {
                    if (record !== rec) return@post
                    DictationEngine.feedExternal(pcm)
                    onAudio(rms)
                }
            }
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Report levels, detect start/end of speech and the no-speech timeout. */
    private fun onAudio(rms: Double) {
        val cb = callback ?: return
        val db = if (rms > 0) (20 * log10(rms)).toFloat() + 60f else -2f // ~ -2..10 like other recognizers
        safe { cb.rmsChanged(db.coerceIn(-2f, 10f)) }
        val level = (rms * 8.5).coerceIn(0.0, 1.0)
        val now = System.currentTimeMillis()
        if (level > SPEECH_LEVEL) {
            if (!speechSeen) safe { cb.beginningOfSpeech() }
            speechSeen = true
            quietSince = 0
        } else if (speechSeen && level < QUIET_LEVEL) {
            if (quietSince == 0L) quietSince = now
            else if (now - quietSince >= silenceLimitMs) stopRecording()
        } else {
            quietSince = 0
        }
        if (!speechSeen && now - startedAt > noSpeechTimeoutMs) {
            record = null
            DictationEngine.cancel(listener)
            finishWith { it.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
        }
    }

    private fun stopRecording() {
        if (record == null) return
        record = null
        if (!sentEnd) { sentEnd = true; callback?.let { cb -> safe { cb.endOfSpeech() } } }
        DictationEngine.stopAndTranscribe()
    }

    private fun sendPartial() {
        val cb = callback ?: return
        val text = (committed.toString() + " " + partial).trim()
        if (text.isEmpty()) return
        safe { cb.partialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }) }
    }

    private fun deliver(text: String) {
        ImeLog.add("recognizer: returned ${text.length} chars")
        finishWith {
            it.results(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
            })
        }
    }

    private fun fail(code: Int) {
        record = null
        finishWith { it.error(code) }
    }

    private inline fun finishWith(action: (Callback) -> Unit) {
        val cb = callback ?: return
        callback = null
        safe { action(cb) }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: RemoteException) { Log.w(TAG, "caller gone: ${e.message}") }
    }

    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            n++
            i += 2
        }
        return if (n == 0) 0.0 else sqrt(sum / n) / 32768.0
    }

    companion object {
        private const val TAG = "WhisperRecognition"
        private const val SAMPLE_RATE = DictationEngine.SAMPLE_RATE
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2 // 100 ms
        private const val SPEECH_LEVEL = 0.15
        private const val QUIET_LEVEL = 0.08
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L
    }
}

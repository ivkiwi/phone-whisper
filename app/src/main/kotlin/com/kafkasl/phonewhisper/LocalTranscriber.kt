package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(
    private val offline: OfflineRecognizer?,
    private val online: OnlineRecognizer?,
    /** Voice activity detector gating streaming sessions; null when disabled or unavailable. */
    private val vad: Vad? = null,
) {

    /** True if the loaded model is a streaming (online) model. */
    val isStreaming: Boolean get() = online != null

    /** An offline model with a VAD can be used live, phrase by phrase ("pseudo-streaming"). */
    val canPseudoStream: Boolean get() = offline != null && vad != null

    /** Audio in, live text out: [StreamingSession] or the phrase-by-phrase [SegmentedSession]. */
    interface LiveSession {
        /** Queue captured PCM float samples for decoding (copied internally). */
        fun accept(samples: FloatArray)
        /** Finalize the current segment now (e.g. punctuation typed mid-dictation). */
        fun cut()
        /** Signal end of audio, wait for decoding to finish, return the full text. */
        fun finish(): String
        /** Discard the session without finalizing. */
        fun abandon()
    }

    /**
     * Pseudo-streaming session for an offline model: the VAD cuts speech into phrases and
     * each phrase is transcribed as soon as it ends (onSegment), so text appears after every
     * pause instead of only when recording stops. Null without a VAD or for streaming models.
     */
    fun newSegmentedSession(
        sampleRate: Int = 16000,
        onUpdate: StreamingSession.UpdateListener?,
    ): LiveSession? {
        val rec = offline ?: return null
        val v = vad ?: return null
        return SegmentedSession(rec, v, sampleRate, onUpdate)
    }

    /**
     * Open an incremental streaming session. Feed audio chunks as they are captured
     * with [StreamingSession.accept] and call [StreamingSession.finish] to get the
     * final text. Returns null for offline models (use [transcribe] instead).
     */
    fun newStreamingSession(
        sampleRate: Int = 16000,
        onUpdate: StreamingSession.UpdateListener? = null,
    ): StreamingSession? =
        online?.let { StreamingSession(it, sampleRate, onUpdate, vad) }

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        online?.let { rec ->
            // Streaming recognizer used in one-shot mode: feed all audio, append a
            // short tail of silence so the encoder emits its final frames, then drain.
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            stream.acceptWaveform(FloatArray(sampleRate / 2), sampleRate)
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text
            stream.release()
            return text.trim()
        }

        val rec = offline!!
        val stream = rec.createStream()
        stream.acceptWaveform(samples, sampleRate)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    /** Explicitly release native resources associated with the recognizer. */
    fun release() {
        offline?.release()
        online?.release()
        vad?.release()
    }

    /**
     * Incremental streaming decode session for an online recognizer.
     *
     * Audio chunks are fed via [accept] (non-blocking — they are queued) and decoded
     * on a dedicated worker thread, so decoding overlaps with recording and the result
     * is ready almost immediately on [finish]. Endpoint detection commits a segment and
     * resets the stream on each detected pause, so long dictations with pauses accumulate
     * cleanly. All native recognizer/stream calls happen only on the worker thread.
     */
    class StreamingSession internal constructor(
        private val rec: OnlineRecognizer,
        private val sampleRate: Int,
        private val onUpdate: UpdateListener? = null,
        private val vad: Vad? = null,
    ) : LiveSession {
        /** Live progress, called on the decoder thread. */
        interface UpdateListener {
            /** The unfinished current segment changed. */
            fun onPartial(text: String)
            /** A segment was finalized (pause, [cut] or end of audio). May be empty after [cut]. */
            fun onSegment(text: String, forced: Boolean)
        }

        private val stream = rec.createStream()
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val committed = StringBuilder()
        @Volatile private var partial = ""
        @Volatile private var aborted = false
        private val lock = Any()

        /** With a VAD only detected speech reaches the recognizer; null = feed everything. */
        private val gate: VadGate? = vad?.let { v ->
            VadGate(
                windowSize = VAD_WINDOW,
                preRollWindows = sampleRate * 6 / 10 / VAD_WINDOW, // ~0.6 s before speech onset
            ) { window ->
                v.acceptWaveform(window)
                val speech = v.isSpeechDetected()
                v.clear() // segments are not used; keep the native queue empty
                speech
            }
        }
        private val events = ArrayList<VadGate.Event>()

        /** Feed speech audio, decode, and report a partial or an endpoint segment. */
        private fun feed(samples: FloatArray) {
            stream.acceptWaveform(samples, sampleRate)
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text
            if (rec.isEndpoint(stream)) {
                commit(text)
                rec.reset(stream)
                if (text.isNotBlank()) onUpdate?.onSegment(text.trim(), forced = false)
            } else {
                val t = text.trim()
                if (t != partial) {
                    partial = t
                    onUpdate?.onPartial(t)
                }
            }
        }

        /**
         * Flush the encoder's right context with a little silence so the last word is not
         * clipped, then finalize the segment and start a fresh one.
         */
        private fun finalizeSegment(forced: Boolean) {
            stream.acceptWaveform(FloatArray(sampleRate * 3 / 5), sampleRate)
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text
            commit(text)
            rec.reset(stream)
            if (forced || text.isNotBlank()) onUpdate?.onSegment(text.trim(), forced)
        }

        private val worker = Thread {
            try {
                vad?.reset()
                while (true) {
                    val chunk = queue.take()
                    if (chunk === POISON) break
                    if (chunk === CUT) {
                        finalizeSegment(forced = true)
                        continue
                    }
                    val g = gate
                    if (g == null) {
                        feed(chunk)
                        continue
                    }
                    events.clear()
                    g.accept(chunk, events)
                    for (e in events) when (e) {
                        is VadGate.Event.Audio -> feed(e.samples)
                        VadGate.Event.SpeechEnd -> finalizeSegment(forced = false)
                    }
                }
                if (!aborted) {
                    // Drain: append a tail of silence so the encoder gets the right-context
                    // it needs to emit the final token(s). Larger models have more decode
                    // latency and otherwise clip the last 1-2 chars of the last word, so be
                    // generous — decoding silence is cheap.
                    stream.acceptWaveform(FloatArray(sampleRate * 6 / 5), sampleRate) // 1.2s
                    stream.inputFinished()
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text
                    commit(text)
                    onUpdate?.onSegment(text.trim(), forced = false)
                }
            } catch (e: Exception) {
                Log.e("StreamingSession", "decode error: ${e.message}")
            } finally {
                stream.release()
            }
        }.apply { isDaemon = true; start() }

        private fun commit(text: String) {
            synchronized(lock) {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    if (committed.isNotEmpty()) committed.append(' ')
                    committed.append(t)
                }
                partial = ""
            }
        }

        /** Queue captured PCM float samples for decoding (copied internally). */
        override fun accept(samples: FloatArray) {
            if (samples.isNotEmpty()) queue.put(samples.copyOf())
        }

        /** Best-effort text so far (committed segments + current partial). Thread-safe. */
        fun currentText(): String = synchronized(lock) {
            (committed.toString() + " " + partial).trim()
        }

        /** Finalize the current segment now (e.g. the user typed punctuation mid-dictation). */
        override fun cut() {
            queue.put(CUT)
        }

        /** Signal end of audio, wait for decoding to finish, return final text. */
        override fun finish(): String {
            queue.put(POISON)
            worker.join()
            return currentText()
        }

        /** Discard the session without finalizing (e.g. recording cancelled). */
        override fun abandon() {
            aborted = true
            queue.put(POISON)
        }

        private companion object {
            private val POISON = FloatArray(0)
            private val CUT = FloatArray(0)
            private const val VAD_WINDOW = 512
        }
    }

    /** See [newSegmentedSession]. All native calls happen on the worker thread. */
    private class SegmentedSession(
        private val rec: OfflineRecognizer,
        private val vad: Vad,
        private val sampleRate: Int,
        private val onUpdate: StreamingSession.UpdateListener?,
    ) : LiveSession {
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val committed = StringBuilder()
        @Volatile private var aborted = false
        private val speech = ArrayList<FloatArray>()
        private var speechSamples = 0
        private val gate = VadGate(VAD_WINDOW, preRollWindows = sampleRate * 3 / 10 / VAD_WINDOW) { window ->
            vad.acceptWaveform(window)
            val s = vad.isSpeechDetected()
            vad.clear()
            s
        }
        private val events = ArrayList<VadGate.Event>()

        private val worker = Thread {
            try {
                vad.reset()
                while (true) {
                    val chunk = queue.take()
                    if (chunk === POISON) break
                    if (chunk === CUT) { decodePhrase(forced = true); continue }
                    events.clear()
                    gate.accept(chunk, events)
                    for (e in events) when (e) {
                        is VadGate.Event.Audio -> {
                            speech += e.samples
                            speechSamples += e.samples.size
                            // Keep phrases bounded: a long monologue is cut every MAX_PHRASE_S.
                            if (speechSamples >= sampleRate * MAX_PHRASE_S) decodePhrase(forced = false)
                        }
                        VadGate.Event.SpeechEnd -> decodePhrase(forced = false)
                    }
                }
                if (!aborted) decodePhrase(forced = false, final = true)
            } catch (e: Exception) {
                Log.e("SegmentedSession", "decode error: ${e.message}")
            }
        }.apply { isDaemon = true; start() }

        /** Transcribe the buffered phrase and report it as a segment. */
        private fun decodePhrase(forced: Boolean, final: Boolean = false) {
            var text = ""
            if (speechSamples > sampleRate / 5) { // ignore blips under 0.2 s
                val samples = FloatArray(speechSamples + sampleRate / 4) // + 0.25 s of silence
                var pos = 0
                for (c in speech) { System.arraycopy(c, 0, samples, pos, c.size); pos += c.size }
                val t0 = System.currentTimeMillis()
                val stream = rec.createStream()
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                text = rec.getResult(stream).text.trim()
                stream.release()
                ImeLog.add("pseudo-stream phrase: ${speechSamples * 1000L / sampleRate} ms audio in ${System.currentTimeMillis() - t0} ms")
            }
            speech.clear()
            speechSamples = 0
            synchronized(committed) {
                if (text.isNotEmpty()) {
                    if (committed.isNotEmpty()) committed.append(' ')
                    committed.append(text)
                }
            }
            if (forced || final || text.isNotEmpty()) onUpdate?.onSegment(text, forced)
        }

        override fun accept(samples: FloatArray) {
            if (samples.isNotEmpty()) queue.put(samples.copyOf())
        }

        override fun cut() { queue.put(CUT) }

        override fun finish(): String {
            queue.put(POISON)
            worker.join()
            return synchronized(committed) { committed.toString() }
        }

        override fun abandon() {
            aborted = true
            queue.put(POISON)
        }

        private companion object {
            private val POISON = FloatArray(0)
            private val CUT = FloatArray(0)
            private const val VAD_WINDOW = 512
            private const val MAX_PHRASE_S = 20
        }
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && isLocalModelInstalled(ctx, it.name) }
                ?.map { it.name } ?: emptyList()
        }

        const val VAD_PREF = "streaming_vad"

        /**
         * Silero VAD (bundled in assets) for streaming sessions, if enabled. A pause of
         * [VAD_MIN_SILENCE_S] ends a speech region; failure just disables gating.
         */
        private fun createVad(ctx: Context, minSilence: Float): Vad? {
            val enabled = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE).getBoolean(VAD_PREF, true)
            if (!enabled) return null
            return try {
                Vad(
                    assetManager = ctx.assets,
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = "silero_vad.onnx",
                            threshold = 0.5f,
                            minSilenceDuration = minSilence,
                            minSpeechDuration = 0.25f,
                            windowSize = 512,
                            maxSpeechDuration = 30f,
                        ),
                        sampleRate = 16000,
                        numThreads = 1,
                    ),
                ).also { Log.i(TAG, "Silero VAD enabled for streaming") }
            } catch (e: Throwable) {
                Log.e(TAG, "VAD unavailable, streaming without it: ${e.message}")
                null
            }
        }

        private const val VAD_MIN_SILENCE_S = 0.5f
        /** Offline models lose context at a cut, so pseudo-streaming waits for a longer pause. */
        private const val PSEUDO_MIN_SILENCE_S = 0.7f

        /** A streaming model is marked by "streaming" in its dir name or a `streaming` marker file. */
        fun isStreamingModelDir(dir: File): Boolean =
            dir.name.contains("streaming", ignoreCase = true) || File(dir, "streaming").exists()

        /** The model that would be loaded now: the selected one if installed, else the first available. */
        fun resolveModelName(ctx: Context): String? {
            val selected = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
                .getString("model_name", "") ?: ""
            if (selected.isNotBlank() && isLocalModelInstalled(ctx, selected)) return selected
            return availableModels(ctx).firstOrNull()
        }

        const val TAP_MODEL_PREF = "tap_model"
        const val PTT_MODEL_PREF = "ptt_model"

        fun isStreamingModel(ctx: Context, name: String): Boolean =
            isStreamingModelDir(File(ctx.filesDir, "models/$name"))

        fun installedStreamingModels(ctx: Context): List<String> =
            availableModels(ctx).filter { isStreamingModel(ctx, it) }

        /** Model of the tap mic (and the floating button): its own choice, else the main selection. */
        fun tapModel(ctx: Context): String? {
            val chosen = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
                .getString(TAP_MODEL_PREF, "") ?: ""
            if (chosen.isNotBlank() && isLocalModelInstalled(ctx, chosen)) return chosen
            return resolveModelName(ctx)
        }

        /**
         * Model of push-to-talk: its own choice (a streaming model, or an offline one used
         * phrase by phrase), else the main model if streaming, else any streaming model,
         * else the main model (pseudo-streaming).
         */
        fun pttModel(ctx: Context): String? {
            val chosen = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
                .getString(PTT_MODEL_PREF, "") ?: ""
            if (chosen.isNotBlank() && isLocalModelInstalled(ctx, chosen)) return chosen
            val main = resolveModelName(ctx)
            main?.takeIf { isStreamingModel(ctx, it) }?.let { return it }
            return installedStreamingModels(ctx).firstOrNull() ?: main
        }

        /** Readable name of an installed model for settings lists. */
        fun displayName(name: String): String =
            MODEL_CATALOG.firstOrNull { it.archive == name }?.name ?: name

        /** Push-to-talk is offered when any local model is installed. */
        fun isPushToTalkAvailable(ctx: Context): Boolean = pttModel(ctx) != null

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            // Streaming (online) transducer models — e.g. Vosk/icefall zipformer2.
            val greedy = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
                .getBoolean("greedy_decoding", false)
            detectStreamingConfig(modelDir, greedy)?.let { onlineConfig ->
                return try {
                    val recognizer = OnlineRecognizer(assetManager = null, config = onlineConfig)
                    Log.i(TAG, "Loaded streaming model: $modelName")
                    LocalTranscriber(offline = null, online = recognizer, vad = createVad(ctx, VAD_MIN_SILENCE_S))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load streaming model: ${e.message}")
                    null
                }
            }

            val config = detectModelConfig(modelDir) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(offline = recognizer, online = null, vad = createVad(ctx, PSEUDO_MIN_SILENCE_S))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /**
         * Detect a streaming zipformer2 transducer (encoder/decoder/joiner + tokens).
         * Distinguished from an offline transducer by "streaming" in the dir name or a
         * `streaming` marker file, since both share the three-file layout.
         *
         * [greedy] selects greedy_search over modified_beam_search. Beam search keeps
         * several competing hypotheses and tends to "hallucinate" plausible words on
         * noise/pauses (more pronounced on the larger model); greedy only emits the
         * single most-likely token, staying more faithful to the audio.
         */
        private fun detectStreamingConfig(dir: File, greedy: Boolean): OnlineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = "$p/tokens.txt"
            if (!File(tokens).exists()) return null
            if (!isStreamingModelDir(dir)) return null

            val encoder = findFile(p, "encoder") ?: return null
            val decoder = findFile(p, "decoder") ?: return null
            val joiner = findFile(p, "joiner") ?: return null

            val decoding = if (greedy) "greedy_search" else "modified_beam_search"

            return OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    modelType = "zipformer2",
                ),
                decodingMethod = decoding,
                maxActivePaths = 10,
                enableEndpoint = true,
            )
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = "$p/tokens.txt"
            if (!File(tokens).exists()) return null

            // Moonshine (has preprocess.onnx)
            if (File("$p/preprocess.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = findFile(p, "encode") ?: return null,
                            uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                            cachedDecoder = findFile(p, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val whisperEncoder = findFile(p, "encoder")
            val whisperDecoder = findFile(p, "decoder")
            if (whisperEncoder != null && whisperDecoder != null && findFile(p, "joiner") == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC or Wav2Vec2/Omnilingual CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                val isOmnilingual = dir.name.contains("wav2vec2") || dir.name.contains("omnilingual") || File(dir, "wav2vec2").exists()
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = if (isOmnilingual) OfflineNemoEncDecCtcModelConfig() else OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        omnilingual = if (isOmnilingual) OfflineOmnilingualAsrCtcModelConfig(model = ctcModel) else OfflineOmnilingualAsrCtcModelConfig(),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            return null
        }

        /** Find first file matching prefix (prefer int8 quantized). */
        private fun findFile(dir: String, prefix: String): String? {
            val d = File(dir)
            // Prefer int8 quantized
            d.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            // Fallback to any onnx/ort
            return d.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}

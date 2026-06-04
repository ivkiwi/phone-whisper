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
) {

    /** True if the loaded model is a streaming (online) model. */
    val isStreaming: Boolean get() = online != null

    /**
     * Open an incremental streaming session. Feed audio chunks as they are captured
     * with [StreamingSession.accept] and call [StreamingSession.finish] to get the
     * final text. Returns null for offline models (use [transcribe] instead).
     */
    fun newStreamingSession(sampleRate: Int = 16000): StreamingSession? =
        online?.let { StreamingSession(it, sampleRate) }

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
    ) {
        private val stream = rec.createStream()
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val committed = StringBuilder()
        @Volatile private var partial = ""
        @Volatile private var aborted = false
        private val lock = Any()

        private val worker = Thread {
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk === POISON) break
                    stream.acceptWaveform(chunk, sampleRate)
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text
                    if (rec.isEndpoint(stream)) {
                        commit(text)
                        rec.reset(stream)
                    } else {
                        partial = text.trim()
                    }
                }
                if (!aborted) {
                    // Drain: append a short tail of silence so the encoder flushes, then finalize.
                    stream.acceptWaveform(FloatArray(sampleRate / 2), sampleRate)
                    stream.inputFinished()
                    while (rec.isReady(stream)) rec.decode(stream)
                    commit(rec.getResult(stream).text)
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
        fun accept(samples: FloatArray) {
            if (samples.isNotEmpty()) queue.put(samples.copyOf())
        }

        /** Best-effort text so far (committed segments + current partial). Thread-safe. */
        fun currentText(): String = synchronized(lock) {
            (committed.toString() + " " + partial).trim()
        }

        /** Signal end of audio, wait for decoding to finish, return final text. */
        fun finish(): String {
            queue.put(POISON)
            worker.join()
            return currentText()
        }

        /** Discard the session without finalizing (e.g. recording cancelled). */
        fun abandon() {
            aborted = true
            queue.put(POISON)
        }

        private companion object {
            private val POISON = FloatArray(0)
        }
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { it.name } ?: emptyList()
        }

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
                    LocalTranscriber(offline = null, online = recognizer)
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
                LocalTranscriber(offline = recognizer, online = null)
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
            val isStreaming = dir.name.contains("streaming", ignoreCase = true) ||
                File(dir, "streaming").exists()
            if (!isStreaming) return null

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

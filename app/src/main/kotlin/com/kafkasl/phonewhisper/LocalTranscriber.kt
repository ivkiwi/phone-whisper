package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(
    private val offline: OfflineRecognizer?,
    private val online: OnlineRecognizer?,
) {

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

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            // Streaming (online) transducer models — e.g. Vosk/icefall zipformer2.
            detectStreamingConfig(modelDir)?.let { onlineConfig ->
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
         */
        private fun detectStreamingConfig(dir: File): OnlineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = "$p/tokens.txt"
            if (!File(tokens).exists()) return null
            val isStreaming = dir.name.contains("streaming", ignoreCase = true) ||
                File(dir, "streaming").exists()
            if (!isStreaming) return null

            val encoder = findFile(p, "encoder") ?: return null
            val decoder = findFile(p, "decoder") ?: return null
            val joiner = findFile(p, "joiner") ?: return null

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
                decodingMethod = "modified_beam_search",
                maxActivePaths = 10,
                enableEndpoint = false,
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

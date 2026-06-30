package com.kafkasl.phonewhisper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.util.concurrent.TimeUnit

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    val url: String? = null,
    /** If set, model files are fetched individually from Hugging Face (no tar archive). */
    val hf: HfSource? = null,
)

/** A single file to download from a Hugging Face repo, placed flat as [local] in the model dir. */
data class HfFile(val remote: String, val local: String, val sizeBytes: Long? = null)

/** A Hugging Face model source: a set of individual files downloaded flat into the model dir. */
data class HfSource(
    val repo: String,
    val revision: String = "main",
    val files: List<HfFile>,
) {
    fun url(f: HfFile) = "https://huggingface.co/$repo/resolve/$revision/${f.remote}"
}

private val VOSK_STREAMING_FILES = listOf(
    HfFile("am-onnx/encoder.int8.onnx", "encoder.int8.onnx"),
    HfFile("am-onnx/decoder.int8.onnx", "decoder.int8.onnx"),
    HfFile("am-onnx/joiner.int8.onnx", "joiner.int8.onnx"),
    HfFile("lang/tokens.txt", "tokens.txt"),
)

// GigaAM v3 (Smirnov75/GigaAM-v3-sherpa-onnx). Remote names are remapped to the flat
// model.onnx / encoder/decoder/joiner + tokens.txt layout our detector expects.
private val GIGAAM_V3_E2E_CTC_FILES = listOf(
    HfFile("gigaam_v3_e2e_ctc_int8.onnx", "model.int8.onnx", 319_869_121),
    HfFile("gigaam_v3_e2e_ctc_tokens.txt", "tokens.txt", 2_006),
)

private val GIGAAM_V3_E2E_RNNT_FILES = listOf(
    HfFile("gigaam_v3_e2e_rnnt_encoder_int8.onnx", "encoder.int8.onnx", 318_995_997),
    HfFile("gigaam_v3_e2e_rnnt_decoder.onnx", "decoder.onnx", 4_600_058),
    HfFile("gigaam_v3_e2e_rnnt_joint.onnx", "joiner.onnx", 2_712_896),
    HfFile("gigaam_v3_e2e_rnnt_tokens.txt", "tokens.txt", 13_353),
)

val MODEL_CATALOG = listOf(
    Model(
        "Russian (Vosk small, streaming)",
        "vosk-model-small-streaming-ru",
        28,
        "★★★★ Offline Russian (streaming)",
        recommended = true,
        hf = HfSource("alphacep/vosk-model-small-streaming-ru", files = VOSK_STREAMING_FILES),
    ),
    Model(
        "Russian (Vosk large, streaming)",
        "vosk-model-streaming-ru",
        72,
        "★★★★★ Offline Russian (streaming, large)",
        hf = HfSource("alphacep/vosk-model-streaming-ru", files = VOSK_STREAMING_FILES),
    ),
    Model(
        "Russian (GigaAM v3 CTC)",
        "gigaam-v3-e2e-ctc-ru",
        319,
        "★★★★★ Offline Russian (punctuation, offline)",
        hf = HfSource("Smirnov75/GigaAM-v3-sherpa-onnx", files = GIGAAM_V3_E2E_CTC_FILES),
    ),
    Model(
        "Russian (GigaAM v3 RNN-T)",
        "gigaam-v3-e2e-rnnt-ru",
        326,
        "★★★★★ Offline Russian (punctuation, most accurate)",
        hf = HfSource("Smirnov75/GigaAM-v3-sherpa-onnx", files = GIGAAM_V3_E2E_RNNT_FILES),
    ),
    Model(
        "Roest Danish Wav2Vec2",
        "roest-v3-wav2vec2-315m",
        315,
        "★★★★★ Offline Danish",
        recommended = false,
        url = "https://github.com/ArtificialTruth/phone-whisper/releases/download/v0.4.0/roest-v3-wav2vec2-315m.tar.bz2"
    ),
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value", recommended = false),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★"),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast"),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model): Boolean {
        val dir = modelDir(ctx, model)
        val hf = model.hf ?: return dir.exists()
        return isCompleteHfModelDir(dir, hf)
    }

    internal fun isCompleteHfModelDir(dir: File, hf: HfSource): Boolean {
        if (!dir.isDirectory) return false
        return hf.files.all { f ->
            val file = File(dir, f.local)
            file.isFile && (f.sizeBytes == null || file.length() == f.sizeBytes)
        }
    }

    /** Download (and extract, if archived) a model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        Thread {
            try {
                val hf = model.hf
                if (hf != null) {
                    downloadHf(ctx, model, hf, onState)
                } else {
                    val url = model.url ?: "$BASE_URL/${model.archive}.tar.bz2"
                    val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
                    try {
                        downloadFile(url, tmpFile, onState)
                        onState(DownloadState.Extracting)
                        extractTarBz2(tmpFile, File(ctx.filesDir, "models"))
                    } finally {
                        tmpFile.delete()
                    }
                }
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            }
        }.start()
    }

    /**
     * Download a model's files individually from Hugging Face into models/<archive>/,
     * flattening remote paths. Writes to a temp dir first, then swaps atomically so a
     * partial download never looks installed.
     */
    private fun downloadHf(
        ctx: Context, model: Model, hf: HfSource, onState: (DownloadState) -> Unit
    ) {
        val finalDir = File(ctx.filesDir, "models/${model.archive}")
        val tmpDir = File(ctx.filesDir, "models/.${model.archive}.partial")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        try {
            val total = model.sizeMb * 1_000_000L
            var done = 0L
            for (f in hf.files) {
                val dest = File(tmpDir, f.local)
                dest.parentFile?.mkdirs()
                val bytes = downloadTo(hf.url(f), dest) { delta ->
                    done += delta
                    if (total > 0)
                        onState(DownloadState.Downloading((done.toFloat() / total).coerceIn(0f, 1f)))
                }
                val expected = f.sizeBytes
                if (expected != null && bytes != expected) {
                    throw IOException("Incomplete download for ${f.local}: $bytes/$expected bytes")
                }
            }
            onState(DownloadState.Extracting) // brief "finishing" state while we swap into place
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) throw IOException("Failed to install model into ${finalDir.name}")
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw e
        }
    }

    /** Download a single URL to [dest], invoking [onDelta] with each chunk's byte count. */
    private fun downloadTo(url: String, dest: File, onDelta: (Int) -> Unit): Long {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response for $url")
            val expected = body.contentLength()
            var written = 0L
            body.byteStream().use { src ->
                FileOutputStream(dest).use { dst ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                        written += n
                        onDelta(n)
                    }
                }
            }
            if (expected >= 0 && written != expected) {
                dest.delete()
                throw IOException("Incomplete download for ${dest.name}: $written/$expected bytes")
            }
            return written
        }
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { src ->
            FileOutputStream(dest).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                require(dest.canonicalPath.startsWith(outDir.canonicalPath)) {
                    "Path traversal: ${entry.name}"
                }
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}

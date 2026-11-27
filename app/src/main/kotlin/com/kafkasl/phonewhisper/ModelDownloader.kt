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
    /** Override to fetch the same flat file set from elsewhere, e.g. a GitHub release. */
    val baseUrl: String = "https://huggingface.co/$repo/resolve/$revision",
) {
    fun url(f: HfFile) = "$baseUrl/${f.remote}"
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

// GigaAM v4 (ai-sage/GigaAM-v4, rnnt revision, e2e head) converted for sherpa-onnx; there is
// no sherpa conversion on Hugging Face yet, so the files live in this fork's models release.
private val GIGAAM_V4_E2E_RNNT_FILES = listOf(
    HfFile("gigaam_v4_e2e_rnnt_encoder_int8.onnx", "encoder.int8.onnx", 239_885_452),
    HfFile("gigaam_v4_e2e_rnnt_decoder.onnx", "decoder.onnx", 4_600_129),
    HfFile("gigaam_v4_e2e_rnnt_joint.onnx", "joiner.onnx", 2_712_897),
    HfFile("gigaam_v4_e2e_rnnt_tokens.txt", "tokens.txt", 9_982),
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
        "Russian + English (GigaAM v4 RNN-T)",
        "gigaam-v4-e2e-rnnt-ru-en",
        248,
        "★★★★★ Offline Russian/English (punctuation, most accurate)",
        hf = HfSource(
            "ivkiwi/phone-whisper",
            files = GIGAAM_V4_E2E_RNNT_FILES,
            baseUrl = "https://github.com/ivkiwi/phone-whisper/releases/download/models-gigaam-v4",
        ),
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
        "★★★★★ Offline Russian (punctuation)",
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
    Model("Parakeet 0.6B v3 (25 languages, incl. Russian)", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Multilingual, punctuation"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast"),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

fun isLocalModelInstalled(ctx: Context, archive: String): Boolean {
    val model = MODEL_CATALOG.firstOrNull { it.archive == archive }
    return if (model != null) {
        ModelDownloader.isInstalled(ctx, model)
    } else {
        File(ctx.filesDir, "models/$archive").exists()
    }
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
        // Two writers would append to the same resumable partial.
        if (!inFlight.add(model.archive)) {
            onState(DownloadState.Error("Already downloading. Keep this screen open."))
            return
        }
        Thread {
            try {
                val hf = model.hf
                if (hf != null) {
                    downloadHf(ctx, model, hf, onState)
                } else {
                    val url = model.url ?: "$BASE_URL/${model.archive}.tar.bz2"
                    val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
                    val total = model.sizeMb * 1_000_000L
                    var done = if (tmpFile.exists()) tmpFile.length() else 0L
                    downloadWithRetry(url, tmpFile) { delta ->
                        done += delta
                        if (total > 0) onState(DownloadState.Downloading((done.toFloat() / total).coerceIn(0f, 1f)))
                    }
                    onState(DownloadState.Extracting)
                    try {
                        extractTarBz2(tmpFile, File(ctx.filesDir, "models"))
                    } finally {
                        tmpFile.delete() // kept on a download failure so ↓ resumes
                        File(tmpFile.path + ".etag").delete()
                    }
                }
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(friendlyError(e)))
            } finally {
                inFlight.remove(model.archive)
            }
        }.start()
    }

    /** Turn network exceptions into advice the user can act on. */
    internal fun friendlyError(e: Exception): String = when (e) {
        is java.net.UnknownHostException ->
            "No internet for this app. Check: Android settings → Apps → this keyboard → " +
                "Mobile data & Wi-Fi (allow, incl. background), Data Saver, firewall / ad-blocker apps. Tap ↓ to resume."
        is java.net.SocketException, is java.net.SocketTimeoutException, is javax.net.ssl.SSLException ->
            "Connection dropped (${e.message ?: e.javaClass.simpleName}). Keep this screen open while downloading; tap ↓ to resume."
        else -> e.message ?: "Unknown error"
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
        tmpDir.mkdirs() // kept after a failure, so the next attempt resumes
        try {
            val total = model.sizeMb * 1_000_000L
            var done = 0L
            for (f in hf.files) {
                val dest = File(tmpDir, f.local)
                dest.parentFile?.mkdirs()
                if (f.sizeBytes != null && dest.length() == f.sizeBytes) { // finished in an earlier attempt
                    done += dest.length()
                    continue
                }
                val bytes = downloadWithRetry(hf.url(f), dest) { delta ->
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
            // Model dirs are scanned by file prefix: no encoder.int8.onnx.etag may get in.
            tmpDir.listFiles()?.filter { it.name.endsWith(".etag") }?.forEach { it.delete() }
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) throw IOException("Failed to install model into ${finalDir.name}")
        } catch (e: IOException) {
            if (isPermanent(e)) tmpDir.deleteRecursively() // wrong file: start over
            throw e
        }
    }

    /**
     * [downloadTo] with up to [ATTEMPTS] tries; each retry resumes from the bytes already in
     * [dest]. Covers mobile-network drops and short DNS failures.
     */
    internal fun downloadWithRetry(url: String, dest: File, onDelta: (Int) -> Unit): Long {
        var lastError: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
            try {
                return downloadTo(url, dest, onDelta)
            } catch (e: IOException) {
                if (isPermanent(e)) throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private val inFlight: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Missing or forbidden files won't appear on retry; 408/429/5xx and network drops may. */
    private fun isPermanent(e: IOException) =
        Regex("^HTTP (401|403|404|410)\\b").containsMatchIn(e.message.orEmpty())

    private const val ATTEMPTS = 5
    private val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)

    /**
     * Download [url] to [dest], resuming after the bytes already in [dest] (HTTP Range);
     * [onDelta] gets each new chunk's byte count. Returns the file's final length.
     * A partial is resumed only if the server still has the version it was written from (its
     * ETag is kept in `<dest>.etag`; callers delete that once the file is in place), so a
     * file replaced on the server is downloaded again from the start.
     */
    private fun downloadTo(url: String, dest: File, onDelta: (Int) -> Unit): Long {
        val etagFile = File(dest.path + ".etag")
        val etag = etagFile.takeIf { it.isFile }?.readText()
        val have = if (etag != null && dest.exists()) dest.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (have > 0) header("Range", "bytes=$have-").header("If-Range", etag!!)
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416 && have > 0) {
                // "Content-Range: bytes */<total>": complete only if the partial is the whole file.
                if (response.header("Content-Range")?.substringAfter("*/")?.toLongOrNull() == have) return have
                dest.delete()
                etagFile.delete()
                throw IOException("Partial ${dest.name} does not match the server; restarting")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response for $url")
            val resumed = response.code == 206 && have > 0
            val newEtag = response.header("ETag")
            // GitHub (Azure) and Hugging Face answer 206 even when If-Range no longer matches.
            if (resumed && newEtag != etag) {
                dest.delete()
                etagFile.delete()
                throw IOException("${dest.name} changed on the server; restarting")
            }
            newEtag?.let { etagFile.writeText(it) } ?: etagFile.delete()
            val expected = if (body.contentLength() >= 0) body.contentLength() + (if (resumed) have else 0) else -1
            var written = if (resumed) have else 0L
            body.byteStream().use { src ->
                FileOutputStream(dest, resumed).use { dst ->
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
                throw IOException("Incomplete download for ${dest.name}: $written/$expected bytes")
            }
            return written
        }
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

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

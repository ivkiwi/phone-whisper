package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * App-wide dictation core shared by the floating-button accessibility service and the
 * voice keyboard: microphone capture, local (sherpa-onnx, incl. streaming) or
 * OpenAI-compatible cloud transcription, and optional cleanup.
 *
 * One instance per process, so the local model is loaded into memory only once no matter
 * how many front ends are enabled. All [Listener] callbacks run on the main thread.
 */
object DictationEngine {

    enum class State { IDLE, RECORDING, TRANSCRIBING }
    enum class StartResult { STARTED, BUSY, NO_PERMISSION, MIC_ERROR }
    enum class Notice { NONE, CLEANUP_NO_KEY, CLEANUP_FAILED }

    interface Listener {
        fun onStateChanged(state: State) {}
        /** Smoothed-ready microphone level in [0, 1] while recording. */
        fun onLevel(level: Float) {}
        fun onResult(text: String, notice: Notice)
        fun onError(message: String)
        /** Live mode only: the unfinished current segment changed. */
        fun onLivePartial(text: String) {}
        /** Live mode only: a segment is final; [forced] after [cutSegment]. */
        fun onLiveSegment(text: String, forced: Boolean) {}
    }

    const val SAMPLE_RATE = 16000
    private const val TAG = "DictationEngine"
    private const val MODEL_IDLE_RELEASE_MS = 2 * 60 * 1000L
    private const val MAX_EXTERNAL_PCM_BYTES = SAMPLE_RATE * 2 * 10 * 60 // 10 minutes

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null

    @Volatile var state = State.IDLE
        private set
    private var listener: Listener? = null
    /** Bumped on every start/cancel so callbacks from an abandoned utterance are dropped. */
    @Volatile private var generation = 0
    private var allowCleanup = true

    /**
     * True while the current utterance streams text live (streaming model loaded and the
     * owner asked for it). Live results skip cleanup here: the owner already typed the
     * segments and runs [cleanup] on what is actually in the editor.
     */
    @Volatile var isLive = false
        private set

    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    @Volatile private var streamSession: LocalTranscriber.LiveSession? = null

    /** Which mic started the utterance: the tap mic and push-to-talk can use different models. */
    enum class Role { TAP, PTT }

    private val modelLock = Any()
    /** Loaded local models by directory name; tap and push-to-talk models can both be in memory. */
    private val transcribers = HashMap<String, LocalTranscriber>()
    private val loading = HashSet<String>()
    /** Local model of the current utterance; null with cloud transcription. */
    @Volatile private var currentModel: String? = null
    @Volatile private var currentUseLocal = true

    private val reloadRunnable = Runnable { reloadModel() }

    private val releaseModelRunnable = Runnable {
        if (state != State.IDLE) { scheduleModelRelease(); return@Runnable }
        thread {
            synchronized(modelLock) {
                if (transcribers.isNotEmpty()) Log.i(TAG, "Releasing ${transcribers.keys} due to inactivity")
                transcribers.values.forEach { it.release() }
                transcribers.clear()
            }
        }
    }

    /** Remember the application context. Call from every front end's onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context = appContext ?: error("DictationEngine.attach() not called")
    private fun prefs(): SharedPreferences = ctx().getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)

    fun isOwner(l: Listener) = listener === l

    // --- Model lifecycle ---

    /** Whether a mic transcribes locally (the tap mic follows the cloud switch; push-to-talk is always local). */
    private fun usesLocal(role: Role) = role == Role.PTT || prefs().getBoolean("use_local", true)

    /** Local model a mic uses, or null for cloud transcription or when none is installed. */
    fun modelFor(role: Role): String? = when {
        !usesLocal(role) -> null
        role == Role.TAP -> LocalTranscriber.tapModel(ctx())
        else -> LocalTranscriber.pttModel(ctx())
    }

    /** Load the tap mic's model in the background (if needed) and arm the idle-release timer. */
    fun preloadModel() {
        thread {
            load(modelFor(Role.TAP))
            scheduleModelRelease()
        }
    }

    /** Load the models of the given mics in the background and keep them loaded. */
    fun ensureModelLoaded(vararg roles: Role = arrayOf(Role.TAP)) {
        handler.removeCallbacks(releaseModelRunnable)
        for (role in roles) {
            val name = modelFor(role) ?: continue
            if (!isLoadedOrLoading(name)) {
                Log.i(TAG, "Pre-loading $name for $role")
                thread { load(name) }
            }
        }
    }

    fun scheduleModelRelease() {
        handler.removeCallbacks(releaseModelRunnable)
        handler.postDelayed(releaseModelRunnable, MODEL_IDLE_RELEASE_MS)
    }

    /** Reload after the selected models or decoding settings change. Lazy if nothing is loaded. */
    fun reloadModel() {
        if (appContext == null) return
        // Never free a recognizer an utterance is still using; retry once it is done.
        if (state != State.IDLE) { handler.removeCallbacks(reloadRunnable); handler.postDelayed(reloadRunnable, 500); return }
        val any = synchronized(modelLock) { transcribers.isNotEmpty() || loading.isNotEmpty() }
        if (!any) return
        thread {
            synchronized(modelLock) {
                Log.i(TAG, "Releasing ${transcribers.keys} for reload")
                transcribers.values.forEach { it.release() }
                transcribers.clear()
            }
            load(modelFor(Role.TAP)) // push-to-talk's model loads again when the keyboard asks for it
            scheduleModelRelease()
        }
    }

    /** Load a specific model in the background (a voice button's own choice). */
    fun ensureLoaded(name: String) {
        handler.removeCallbacks(releaseModelRunnable)
        if (!isLoadedOrLoading(name)) thread { load(name) }
    }

    private fun isLoadedOrLoading(name: String) = synchronized(modelLock) { name in transcribers || name in loading }
    private fun isLoading(name: String) = synchronized(modelLock) { name in loading }
    private fun loaded(name: String?): LocalTranscriber? = name?.let { synchronized(modelLock) { transcribers[it] } }

    private fun load(name: String?) {
        if (name == null) return
        synchronized(modelLock) {
            if (name in transcribers || name in loading) return
            loading += name
        }
        try {
            val t0 = System.currentTimeMillis()
            // Native libraries exist for arm64-v8a only: never let a missing one crash the keyboard.
            val transcriber = try {
                LocalTranscriber.create(ctx(), name)
            } catch (t: Throwable) {
                Log.e(TAG, "Model $name failed to load", t)
                null
            }
            synchronized(modelLock) { if (transcriber != null) transcribers[name] = transcriber }
            Log.i(TAG, if (transcriber != null) "Model ready: $name in ${System.currentTimeMillis() - t0}ms" else "Model failed: $name")
            ImeLog.add(if (transcriber != null) "model loaded: $name (${System.currentTimeMillis() - t0} ms)" else "model failed: $name")
        } finally {
            synchronized(modelLock) { loading -= name }
        }
    }

    // --- Recording ---

    /**
     * Start capturing for [owner]. With [cleanup] false the transcript skips post-processing
     * (used when re-dictating a single word).
     */
    /**
     * [model]: a specific local model for this utterance (a voice button's own choice);
     * null uses the model of [role]. [role] PTT makes offline models pseudo-stream.
     */
    fun start(
        owner: Listener, cleanup: Boolean = true, live: Boolean = false,
        role: Role = Role.TAP, model: String? = null,
    ): StartResult {
        if (state != State.IDLE) return StartResult.BUSY
        val context = ctx()
        if (model != null) ensureLoaded(model) else ensureModelLoaded(role)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return StartResult.NO_PERMISSION
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) {
            return StartResult.NO_PERMISSION
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return StartResult.MIC_ERROR
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return StartResult.MIC_ERROR
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            record.release()
            return StartResult.MIC_ERROR
        }

        val gen = begin(owner, cleanup, live, role, model, record)

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING && generation == gen) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    val level = audioLevel(buf, n)
                    handler.post { if (generation == gen) listener?.onLevel(level) }
                    streamSession?.accept(PcmConverter.toFloatSamples(buf, n))
                }
            }
        }
        return StartResult.STARTED
    }

    /**
     * Start an utterance whose audio comes from another process (the HeliBoard fork records
     * itself, since a background app may not use the microphone). Feed 16 kHz mono 16-bit
     * little-endian PCM with [feedExternal], then [stopAndTranscribe] as usual.
     */
    fun startExternal(
        owner: Listener, cleanup: Boolean = true, live: Boolean = false,
        role: Role = Role.TAP, model: String? = null,
    ): StartResult {
        if (state != State.IDLE) return StartResult.BUSY
        if (model != null) ensureLoaded(model) else ensureModelLoaded(role)
        begin(owner, cleanup, live, role, model, null)
        return StartResult.STARTED
    }

    /** Audio for an utterance started with [startExternal]. Main thread. */
    fun feedExternal(pcm: ByteArray) {
        if (state != State.RECORDING || audioRecord != null || pcm.isEmpty()) return
        // Another process feeds this: bound the utterance instead of growing memory forever.
        if ((pcmStream?.size() ?: 0) >= MAX_EXTERNAL_PCM_BYTES) { stopAndTranscribe(); return }
        pcmStream?.write(pcm, 0, pcm.size)
        streamSession?.accept(PcmConverter.toFloatSamples(pcm, pcm.size))
    }

    private fun begin(owner: Listener, cleanup: Boolean, live: Boolean, role: Role, explicitModel: String?, record: AudioRecord?): Int {
        val useLocal = explicitModel != null || usesLocal(role)
        val model = explicitModel ?: modelFor(role)
        currentUseLocal = useLocal
        currentModel = model
        ImeLog.add("dictation start: $role ${if (useLocal) model ?: "(no local model)" else "cloud"}")
        val gen = ++generation
        listener = owner
        audioRecord = record
        pcmStream = ByteArrayOutputStream()
        // If a streaming model is already loaded, decode incrementally while recording so
        // the result is ready almost instantly on stop. Otherwise fall back to one-shot.
        val local = if (useLocal) loaded(model) else null
        // Streaming models decode while recording (live if asked). Offline models go live
        // phrase by phrase only for push-to-talk; the tap mic transcribes them in one piece.
        val session: LocalTranscriber.LiveSession? = when {
            local == null -> null
            local.isStreaming -> local.newStreamingSession(SAMPLE_RATE, if (live) liveUpdates(gen) else null)
            live && role == Role.PTT -> local.newSegmentedSession(SAMPLE_RATE, liveUpdates(gen))
            else -> null
        }
        isLive = live && session != null
        allowCleanup = cleanup && !isLive
        streamSession = session

        state = State.RECORDING
        owner.onStateChanged(State.RECORDING)
        return gen
    }

    private fun liveUpdates(gen: Int) = object : LocalTranscriber.StreamingSession.UpdateListener {
        override fun onPartial(text: String) {
            handler.post { if (generation == gen) listener?.onLivePartial(text) }
        }

        override fun onSegment(text: String, forced: Boolean) {
            handler.post { if (generation == gen) listener?.onLiveSegment(text, forced) }
        }
    }

    /** Live mode: finalize the segment being spoken now. Answered by onLiveSegment(forced = true). */
    fun cutSegment(): Boolean {
        if (!isLive || state != State.RECORDING) return false
        val session = streamSession ?: return false
        session.cut()
        return true
    }

    fun stopAndTranscribe() {
        if (state != State.RECORDING) return
        val gen = generation
        setState(State.TRANSCRIBING)

        stopAudio()
        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null
        val session = streamSession
        streamSession = null

        if (pcm.isEmpty()) { session?.abandon(); fail(gen, "No audio captured"); return }

        val model = currentModel
        if (currentUseLocal && session != null) {
            finishStreaming(gen, session)
        } else if (currentUseLocal) {
            if (model == null) { fail(gen, "No local model installed. Download one in the Phone Whisper app."); return }
            thread {
                var attempts = 0
                // Wait up to 30 seconds for the model if it is still loading (large models take a while).
                while (loaded(model) == null && isLoading(model) && attempts < 300) {
                    Thread.sleep(100)
                    attempts++
                }
                if (loaded(model) == null && !isLoading(model)) load(model)
                val local = loaded(model)
                if (local != null) transcribeLocal(gen, pcm, local)
                else fail(gen, "Model $model could not be loaded. Please try again.")
            }
        } else {
            transcribeApi(gen, pcm)
        }
    }

    /** Abort whatever [owner] is doing (recording or transcribing); its result is dropped. */
    fun cancel(owner: Listener, onlyWhileRecording: Boolean = false) {
        if (listener !== owner || state == State.IDLE) return
        if (onlyWhileRecording && state != State.RECORDING) return
        Log.i(TAG, "Cancelling dictation in state $state")
        generation++
        stopAudio()
        streamSession?.abandon()
        streamSession = null
        pcmStream = null
        listener = null
        isLive = false
        state = State.IDLE
        owner.onStateChanged(State.IDLE)
    }

    private fun stopAudio() {
        audioRecord?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null
    }

    private fun setState(s: State) {
        state = s
        listener?.onStateChanged(s)
    }

    // --- Transcription ---

    private fun finishStreaming(gen: Int, session: LocalTranscriber.LiveSession) {
        thread {
            try {
                val t0 = System.currentTimeMillis()
                val text = session.finish()
                Log.i(TAG, "Streaming transcription drained in ${System.currentTimeMillis() - t0}ms")
                handleTranscript(gen, text)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming transcription failed", e)
                scheduleModelRelease()
                fail(gen, "Local error: ${e.message}")
            }
        }
    }

    private fun transcribeLocal(gen: Int, pcm: ByteArray, transcriber: LocalTranscriber) {
        try {
            val samples = PcmConverter.toFloatSamples(pcm, pcm.size)
            val t0 = System.currentTimeMillis()
            val text = transcriber.transcribe(samples, SAMPLE_RATE)
            Log.i(TAG, "Local transcription: ${System.currentTimeMillis() - t0}ms, ${samples.size / SAMPLE_RATE}s audio")
            handleTranscript(gen, text)
        } catch (e: Exception) {
            Log.e(TAG, "Local transcription failed", e)
            scheduleModelRelease()
            fail(gen, "Local error: ${e.message}")
        }
    }

    private fun transcribeApi(gen: Int, pcm: ByteArray) {
        val prefs = prefs()
        val wav = WavWriter.encode(pcm)
        val apiKey = OpenAiCompatibleApi.apiKey(prefs, OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF)
        val apiBaseUrl = OpenAiCompatibleApi.apiBaseUrl(prefs, OpenAiCompatibleApi.TRANSCRIPTION_API_BASE_URL_PREF)
        val model = OpenAiCompatibleApi.model(
            prefs, OpenAiCompatibleApi.TRANSCRIPTION_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL
        )
        if (apiKey.isBlank()) { fail(gen, "Set transcription API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(wav, apiKey, apiBaseUrl, model) { result ->
            if (!result.text.isNullOrBlank()) handleTranscript(gen, result.text)
            else fail(gen, "Error: ${result.error ?: "empty transcript"}")
        }
    }

    private fun handleTranscript(gen: Int, text: String?) {
        scheduleModelRelease()
        if (gen != generation) return // cancelled: don't send the text to the cleanup endpoint
        if (text.isNullOrBlank()) { fail(gen, "No speech detected"); return }

        if (!allowCleanup) { succeed(gen, text, Notice.NONE); return }
        cleanup(text) { cleaned, notice -> succeed(gen, cleaned, notice) }
    }

    fun isCleanupEnabled(): Boolean = prefs().getBoolean("use_post_processing", false)

    /**
     * Run the configured cleanup on [text]; the callback gets the cleaned text (or the
     * original plus a notice) on a background thread. No-op when cleanup is disabled.
     */
    fun cleanup(text: String, callback: (String, Notice) -> Unit) {
        val prefs = prefs()
        if (!prefs.getBoolean("use_post_processing", false)) { callback(text, Notice.NONE); return }
        val apiKey = OpenAiCompatibleApi.apiKey(prefs, OpenAiCompatibleApi.CLEANUP_API_KEY_PREF)
        if (apiKey.isBlank()) { callback(text, Notice.CLEANUP_NO_KEY); return }

        val apiBaseUrl = OpenAiCompatibleApi.apiBaseUrl(prefs, OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF)
        val model = OpenAiCompatibleApi.model(
            prefs, OpenAiCompatibleApi.CLEANUP_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL
        )
        val prompt = prefs.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        PostProcessor.process(text, prompt, apiKey, apiBaseUrl, model) { result ->
            if (!result.text.isNullOrBlank()) callback(result.text, Notice.NONE)
            else callback(text, Notice.CLEANUP_FAILED)
        }
    }

    private fun succeed(gen: Int, text: String, notice: Notice) =
        finish(gen) { it.onResult(text, notice) }

    private fun fail(gen: Int, message: String) =
        finish(gen) { it.onError(message) }

    /** Return to IDLE on the main thread and deliver to the owner, unless the utterance was cancelled. */
    private fun finish(gen: Int, deliver: (Listener) -> Unit) {
        handler.post {
            if (gen != generation) return@post
            val l = listener
            listener = null
            isLive = false
            state = State.IDLE
            l?.onStateChanged(State.IDLE)
            l?.let(deliver)
        }
    }

    /** RMS level of a PCM chunk scaled for the recording animation. */
    private fun audioLevel(buf: ByteArray, byteCount: Int): Float {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sum / samples) / 32768.0
        return (rms * 8.5).coerceIn(0.02, 1.0).toFloat()
    }
}

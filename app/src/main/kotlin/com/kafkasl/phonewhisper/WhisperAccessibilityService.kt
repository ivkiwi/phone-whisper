package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var equalizerView: EqualizerView? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    @Volatile
    private var streamSession: LocalTranscriber.StreamingSession? = null
    private val handler = Handler(Looper.getMainLooper())
    // Smoothed microphone level driving the recording animation (button scale + equalizer).
    @Volatile private var targetRecordingLevel = 0f
    private var renderedRecordingLevel = 0f
    private val animateRecordingLevel = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            renderedRecordingLevel += (targetRecordingLevel - renderedRecordingLevel) * 0.4f
            val scale = 1f + renderedRecordingLevel * 0.4f
            button?.scaleX = scale
            button?.scaleY = scale
            equalizerView?.setLevel(renderedRecordingLevel)
            handler.postDelayed(this, 16)
        }
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) cancelRecording("screen_off")
        }
    }
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Local transcription engine (loaded dynamically)
    @Volatile
    private var localTranscriber: LocalTranscriber? = null

    @Volatile
    private var isModelLoading = false

    private val releaseModelRunnable = Runnable {
        thread {
            synchronized(this) {
                localTranscriber?.let {
                    Log.i(TAG, "Releasing local transcriber model due to inactivity")
                    it.release()
                    localTranscriber = null
                }
            }
        }
    }

    private fun resetModelReleaseTimer() {
        handler.removeCallbacks(releaseModelRunnable)
        handler.postDelayed(releaseModelRunnable, 2 * 60 * 1000) // 2 minutes
    }

    private fun ensureModelLoaded() {
        handler.removeCallbacks(releaseModelRunnable)
        if (localTranscriber == null) {
            Log.i(TAG, "Pre-loading model...")
            thread { initLocalModel() }
        }
    }

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        registerScreenReceiver()
        showOverlay()
        // Try to load local model in background, and schedule release if unused
        thread {
            initLocalModel()
            resetModelReleaseTimer()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        unregisterScreenReceiver()
        cancelRecording("service_destroy")
        removeOverlay()
        handler.removeCallbacks(releaseModelRunnable)
        streamSession?.abandon()
        streamSession = null
        synchronized(this) {
            localTranscriber?.let {
                Log.i(TAG, "Releasing model during service destroy")
                it.release()
                localTranscriber = null
            }
        }
        super.onDestroy()
    }

    private fun initLocalModel() {
        synchronized(this) {
            if (localTranscriber != null) return
            if (isModelLoading) return
            isModelLoading = true
        }
        try {
            val modelName = prefs().getString("model_name", "") ?: ""
            val transcriber = if (modelName.isBlank() || !isLocalModelInstalled(this, modelName)) {
                // Auto-detect first available model
                val models = LocalTranscriber.availableModels(this)
                if (models.isNotEmpty()) {
                    Log.i(TAG, "Auto-detected model: ${models.first()}")
                    LocalTranscriber.create(this, models.first())
                } else null
            } else {
                LocalTranscriber.create(this, modelName)
            }
            synchronized(this) {
                localTranscriber = transcriber
                if (transcriber != null) {
                    Log.i(TAG, "Local transcription ready")
                } else {
                    Log.i(TAG, "No local model found, will use API")
                }
            }
        } finally {
            synchronized(this) {
                isModelLoading = false
            }
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() {
        thread {
            synchronized(this) {
                localTranscriber?.let {
                    Log.i(TAG, "Releasing old model for reload")
                    it.release()
                    localTranscriber = null
                }
            }
            initLocalModel()
            resetModelReleaseTimer()
        }
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val equalizer = EqualizerView(this).apply { visibility = View.GONE }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(equalizer, FrameLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt(), Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    // The overlay window is larger than the visible button (for the ring).
                    // Ignore touches that land outside the button circle so they pass
                    // through to the app underneath instead of being swallowed.
                    if (!isInsideButton(ev.x, ev.y, ringSize, buttonSize))
                        return@setOnTouchListener false
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        equalizerView = equalizer
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    /** True if (x,y) within the overlay window falls inside the centered button circle. */
    private fun isInsideButton(x: Float, y: Float, overlaySize: Int, buttonSize: Int): Boolean {
        val center = overlaySize / 2f
        val radius = buttonSize / 2f
        val dx = x - center
        val dy = y - center
        return dx * dx + dy * dy <= radius * radius
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        equalizerView = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    /** Show the voice-reactive recording visual: hide the mic icon, reveal the equalizer. */
    private fun startRecordingAnimation() {
        targetRecordingLevel = 0f
        renderedRecordingLevel = 0f
        button?.animate()?.cancel()
        button?.alpha = 1f
        button?.setImageDrawable(null)
        equalizerView?.reset()
        equalizerView?.visibility = View.VISIBLE
        handler.removeCallbacks(animateRecordingLevel)
        handler.post(animateRecordingLevel)
    }

    /** Tear down the recording visual and restore the idle mic button. */
    private fun stopRecordingAnimation() {
        handler.removeCallbacks(animateRecordingLevel)
        targetRecordingLevel = 0f
        renderedRecordingLevel = 0f
        button?.animate()?.cancel()
        button?.scaleX = 1f
        button?.scaleY = 1f
        button?.alpha = 1f
        button?.setImageResource(R.drawable.ic_mic)
        equalizerView?.visibility = View.GONE
        equalizerView?.reset()
    }

    /** Compute an RMS level from a PCM chunk and feed it to the recording animation. */
    private fun updateAudioLevel(buf: ByteArray, byteCount: Int) {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < byteCount) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        if (samples == 0) return
        val rms = sqrt(sum / samples) / 32768.0
        targetRecordingLevel = (rms * 8.5).coerceIn(0.02, 1.0).toFloat()
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal) {
            ensureModelLoaded()
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        pcmStream = ByteArrayOutputStream()
        // If a streaming model is already loaded, decode incrementally while recording so
        // the result is ready almost instantly on stop. Otherwise fall back to one-shot.
        val local = localTranscriber
        streamSession = if (useLocal && local != null && local.isStreaming)
            local.newStreamingSession(SAMPLE_RATE) else null

        audioRecord!!.startRecording()
        state = State.RECORDING
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startRecordingAnimation()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    updateAudioLevel(buf, n)
                    streamSession?.let { feedPcmChunk(it, buf, n) }
                }
            }
        }
    }

    /** Convert 16-bit little-endian PCM bytes to float samples and queue them. */
    private fun feedPcmChunk(session: LocalTranscriber.StreamingSession, buf: ByteArray, n: Int) {
        val count = n / 2
        val fa = FloatArray(count)
        for (i in 0 until count) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt()
            fa[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        session.accept(fa)
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopRecordingAnimation()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null
        val session = streamSession
        streamSession = null

        if (pcm.isEmpty()) { session?.abandon(); reset("No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal && session != null) {
            finishStreaming(session)
        } else if (useLocal) {
            thread {
                var attempts = 0
                // Wait up to 10 seconds (100 * 100ms) for local model to load if in progress
                while (localTranscriber == null && isModelLoading && attempts < 100) {
                    Thread.sleep(100)
                    attempts++
                }
                val local = localTranscriber
                if (local != null) {
                    transcribeLocal(pcm, local)
                } else {
                    handler.post {
                        reset("Local model not loaded yet. Please try again.")
                    }
                }
            }
        } else {
            transcribeApi(pcm)
        }
    }

    /** Drain an incremental streaming session and route its final text. */
    private fun finishStreaming(session: LocalTranscriber.StreamingSession) {
        thread {
            try {
                val t0 = System.currentTimeMillis()
                val text = session.finish()
                Log.i(TAG, "Streaming transcription drained in ${System.currentTimeMillis() - t0}ms")
                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming transcription failed", e)
                resetModelReleaseTimer()
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                resetModelReleaseTimer()
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = apiKey(OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF)
        val apiBaseUrl = apiBaseUrl(OpenAiCompatibleApi.TRANSCRIPTION_API_BASE_URL_PREF)
        val model = prefs().getString(OpenAiCompatibleApi.TRANSCRIPTION_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL)
            ?: OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL
        if (apiKey.isBlank()) { reset("Set transcription API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(wav, apiKey, apiBaseUrl, model) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        resetModelReleaseTimer()
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = apiKey(OpenAiCompatibleApi.CLEANUP_API_KEY_PREF)
        val apiBaseUrl = apiBaseUrl(OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF)
        val model = prefs().getString(OpenAiCompatibleApi.CLEANUP_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL)
            ?: OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Cleanup needs API key. Using raw text.")
                    injectText(text)
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            
            PostProcessor.process(text, prompt, apiKey, apiBaseUrl, model) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                    }
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        } else {
            handler.post {
                injectText(text)
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    /** Abort an in-progress recording without transcribing (e.g. screen turned off). */
    private fun cancelRecording(reason: String) {
        if (state != State.RECORDING) return
        Log.i(TAG, "Cancelling recording: $reason")
        state = State.IDLE
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null
        streamSession?.abandon()
        streamSession = null
        pcmStream = null
        stopRecordingAnimation()
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be gone if Android tore down the service process.
        }
        screenReceiverRegistered = false
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            // Resolve the field's real existing text: placeholders/hints (esp. in web
            // editors) are reported as the value and must not be prepended to dictation.
            val resolved = InjectionText.resolveEditableText(
                rawText = node.text?.toString().orEmpty(),
                hintText = node.hintText?.toString().orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                packageName = node.packageName?.toString().orEmpty(),
                isFocused = node.isFocused,
                selectionStart = node.textSelectionStart,
                selectionEnd = node.textSelectionEnd,
            )
            val current = resolved.text
            val updated = if (current.isEmpty()) {
                resolved.ignoredReason?.let { Log.i(TAG, "Treating field text as empty ($it)") }
                text
            } else {
                val start = if (node.textSelectionStart in 0..current.length) node.textSelectionStart else current.length
                val end = if (node.textSelectionEnd in 0..current.length) node.textSelectionEnd else start
                current.replaceRange(minOf(start, end), maxOf(start, end), text)
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun apiKey(prefKey: String) =
        prefs().getString(prefKey, null)?.takeIf { it.isNotBlank() }
            ?: prefs().getString(OpenAiCompatibleApi.LEGACY_API_KEY_PREF, "") ?: ""
    private fun apiBaseUrl(prefKey: String) =
        OpenAiCompatibleApi.normalizedBaseUrl(
            prefs().getString(prefKey, null)?.takeIf { it.isNotBlank() }
                ?: prefs().getString(OpenAiCompatibleApi.LEGACY_API_BASE_URL_PREF, OpenAiCompatibleApi.DEFAULT_BASE_URL)
        )
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}

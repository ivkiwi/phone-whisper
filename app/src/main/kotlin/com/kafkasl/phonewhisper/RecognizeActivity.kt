package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Stand-alone voice input for any app or keyboard, like FUTO Voice Input: answers
 * android.speech.action.RECOGNIZE_SPEECH with a floating window that starts listening at
 * once, shows the text as it is recognised, stops on "Done" or after a pause, and returns
 * the text (RecognizerIntent.EXTRA_RESULTS). The model and mode come from the voice button
 * chosen in the app ("Voice input popup").
 */
class RecognizeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var mic: FrameLayout
    private lateinit var doneButton: Button

    private val committed = StringBuilder()
    private var partial = ""
    private var finished = false
    /** The engine streams this utterance live (no engine-side cleanup). */
    private var live = false
    private var started = false

    // Auto-stop: after speech was heard, this much quiet ends the recording.
    private var silenceLimitMs = 0L
    private var speechSeen = false
    private var quietSince = 0L

    private val listener = object : DictationEngine.Listener {
        override fun onStateChanged(state: DictationEngine.State) {
            if (state == DictationEngine.State.RECORDING) live = DictationEngine.isLive
            title.setText(
                when (state) {
                    DictationEngine.State.RECORDING -> R.string.popup_listening
                    DictationEngine.State.TRANSCRIBING -> R.string.popup_transcribing
                    DictationEngine.State.IDLE -> R.string.popup_listening
                }
            )
            mic.background = oval(if (state == DictationEngine.State.RECORDING) REC_COLOR else BUSY_COLOR)
            doneButton.isEnabled = state == DictationEngine.State.RECORDING
            if (state != DictationEngine.State.RECORDING) { mic.scaleX = 1f; mic.scaleY = 1f }
        }

        override fun onLevel(level: Float) {
            val s = 1f + level * 0.25f
            mic.scaleX = s
            mic.scaleY = s
            checkSilence(level)
        }

        override fun onLivePartial(text: String) {
            partial = text
            showText()
        }

        override fun onLiveSegment(text: String, forced: Boolean) {
            if (text.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text.trim())
            }
            partial = ""
            showText()
        }

        override fun onResult(text: String, notice: DictationEngine.Notice) {
            val raw = text.ifBlank { committed.toString() }.trim()
            if (raw.isEmpty()) { finishWith(null); return }
            if (live && DictationEngine.isCleanupEnabled()) {
                // Live results are not cleaned by the engine; do it before returning.
                title.setText(R.string.popup_cleaning)
                DictationEngine.cleanup(raw) { cleaned, _ -> handler.post { finishWith(cleaned.trim().ifBlank { raw }) } }
            } else {
                finishWith(raw)
            }
        }

        override fun onError(message: String) {
            title.text = message
            handler.postDelayed({ finishWith(committed.toString().trim().ifBlank { null }) }, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // It listens as soon as it opens: don't let another app's overlay hide that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        setFinishOnTouchOutside(false)
        DictationEngine.attach(this)
        ImeLog.init(this)
        setContentView(buildView())
        val extraSilence = intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
        silenceLimitMs = if (extraSilence > 0) extraSilence else popupSilenceMs(this)
        ImeLog.add("popup: RECOGNIZE_SPEECH from ${callingPackage ?: "?"}")
    }

    override fun onStart() {
        super.onStart()
        if (started) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else finishWith(null)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the window (home, another app) abandons the recording.
        if (!finished) { // handled config changes (manifest) never get here
            DictationEngine.cancel(listener)
            finishWith(null)
        }
    }

    private fun startListening() {
        started = true
        val button = popupButton(this)
        if (!VoiceButtons.isReady(this, button)) {
            title.setText(R.string.ime_needs_model)
            handler.postDelayed({ finishWith(null) }, 2500)
            return
        }
        val role = if (button.pushToTalk) DictationEngine.Role.PTT else DictationEngine.Role.TAP
        val model = button.model.takeIf { it.isNotBlank() }?.let { VoiceButtons.resolveModel(this, button) }
        when (DictationEngine.start(listener, cleanup = true, live = true, role = role, model = model)) {
            DictationEngine.StartResult.STARTED -> {}
            DictationEngine.StartResult.BUSY -> { title.setText(R.string.ime_busy); handler.postDelayed({ finishWith(null) }, 1500) }
            DictationEngine.StartResult.MIC_ERROR -> { title.setText(R.string.ime_mic_error); handler.postDelayed({ finishWith(null) }, 1500) }
            DictationEngine.StartResult.NO_PERMISSION -> finishWith(null)
        }
    }

    private fun checkSilence(level: Float) {
        if (silenceLimitMs <= 0 || DictationEngine.state != DictationEngine.State.RECORDING) return
        val now = System.currentTimeMillis()
        if (level > SPEECH_LEVEL) {
            speechSeen = true
            quietSince = 0
        } else if (speechSeen && level < QUIET_LEVEL) {
            if (quietSince == 0L) quietSince = now
            else if (now - quietSince >= silenceLimitMs) DictationEngine.stopAndTranscribe()
        } else {
            quietSince = 0
        }
    }

    private fun showText() {
        val shown = (committed.toString() + " " + partial).trim()
        text.text = shown.ifEmpty { getString(R.string.popup_hint) }
        text.alpha = if (shown.isEmpty()) 0.6f else 1f
    }

    /** Return [result] to the caller (null = cancelled) and close. */
    private fun finishWith(result: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (result.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
        } else {
            ImeLog.add("popup: returned ${result.length} chars")
            setResult(RESULT_OK, Intent().apply {
                putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(result))
                putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f))
            })
        }
        finish()
    }

    private fun buildView(): LinearLayout {
        val pad = dp(20)
        title = TextView(this).apply {
            setText(R.string.popup_listening)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFFFFFFF.toInt())
            val p = dp(16); setPadding(p, p, p, p)
        }
        mic = FrameLayout(this).apply {
            background = oval(BUSY_COLOR)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { if (DictationEngine.state == DictationEngine.State.RECORDING) DictationEngine.stopAndTranscribe() }
        }
        text = TextView(this).apply {
            setText(R.string.popup_hint)
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            minLines = 3
            maxLines = 8
            gravity = Gravity.CENTER
        }
        val cancel = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(android.R.string.cancel)
            setOnClickListener { DictationEngine.cancel(listener); finishWith(null) }
        }
        doneButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.popup_done)
            isEnabled = false
            setOnClickListener { DictationEngine.stopAndTranscribe() }
        }
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(cancel)
            addView(doneButton)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(mic, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(16), 0, dp(16))
            })
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            minimumWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        }
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val BUTTON_PREF = "popup_button"
        const val SILENCE_PREF = "popup_silence_ms"
        const val DEFAULT_SILENCE_MS = 2000L
        private const val SPEECH_LEVEL = 0.15f
        private const val QUIET_LEVEL = 0.08f
        private const val REC_COLOR = 0xFFEF4444.toInt()
        private const val BUSY_COLOR = 0xFF8A9099.toInt()

        /** Voice button whose model and mode the popup uses (default: the first one). */
        fun popupButton(context: Context): VoiceButton {
            val buttons = VoiceButtons.load(context)
            val id = context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE).getString(BUTTON_PREF, "")
            return buttons.firstOrNull { it.id == id } ?: buttons.first()
        }

        /** Pause after speech that ends the recording; 0 = only the Done button. */
        fun popupSilenceMs(context: Context): Long =
            context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE).getLong(SILENCE_PREF, DEFAULT_SILENCE_MS)
    }
}

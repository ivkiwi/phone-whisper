package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import androidx.lifecycle.Lifecycle

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var updateRowSub: TextView
    private var pendingUpdate: AppUpdate? = null
    private var checkingUpdate = false
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var imeRowSub: TextView
    private lateinit var buttonsContainer: LinearLayout
    private lateinit var cloudConfigContainer: LinearLayout
    private lateinit var cleanupConfigContainer: LinearLayout
    private lateinit var transcriptionKeyRowSub: TextView
    private lateinit var transcriptionBaseUrlRowSub: TextView
    private lateinit var transcriptionModelRowSub: TextView
    private lateinit var cleanupKeyRowSub: TextView
    private lateinit var cleanupBaseUrlRowSub: TextView
    private lateinit var cleanupModelRowSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        // Top large header (like "Connected devices")
        val header = TextView(this).apply {
            text = "Phone Whisper"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        val updateRow = settingsRow("App updates", "Checking...") { onUpdateTap() }
        updateRowSub = updateRow.findViewWithTag("subtitle")
        root.addView(updateRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))
        
        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val imeRow = settingsRow("Voice keyboard", "Checking...") {
            if (isImeEnabled()) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            } else {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        imeRowSub = imeRow.findViewWithTag("subtitle")
        root.addView(imeRow)

        root.addView(settingsRow("Keyboard diagnostics log", "Events of the voice keyboard, to copy into a bug report") {
            showImeLog()
        })

        val accRow = settingsRow("Accessibility service (floating button)", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- Voice buttons ---
        root.addView(sectionHeader("Voice buttons"))
        buttonsContainer = vertical(0)
        root.addView(buttonsContainer)

        // --- Engine Section ---
        
        val isCloud = !prefs().getBoolean("use_local", true)
        
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "Requires OpenAI-compatible API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        root.addView(cloudRow)

        cloudConfigContainer = vertical(0)
        cloudConfigContainer.addView(sectionHeader("Cloud transcription"))
        val transcriptionKeyRow = settingsRow("Transcription API key", "Tap to set") {
            promptApiKey(OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF, "Transcription API key")
        }
        transcriptionKeyRowSub = transcriptionKeyRow.findViewWithTag("subtitle")
        cloudConfigContainer.addView(transcriptionKeyRow)

        val transcriptionBaseUrlRow = settingsRow("Transcription API base URL", "Tap to set") {
            promptApiBaseUrl(OpenAiCompatibleApi.TRANSCRIPTION_API_BASE_URL_PREF, "Transcription API base URL")
        }
        transcriptionBaseUrlRowSub = transcriptionBaseUrlRow.findViewWithTag("subtitle")
        cloudConfigContainer.addView(transcriptionBaseUrlRow)

        val transcriptionModelRow = settingsRow("Transcription model", OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL) {
            promptApiModel(
                title = "Transcription model",
                prefKey = OpenAiCompatibleApi.TRANSCRIPTION_MODEL_PREF,
                defaultModel = OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL,
                keyPref = OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF,
                baseUrlPref = OpenAiCompatibleApi.TRANSCRIPTION_API_BASE_URL_PREF
            )
        }
        transcriptionModelRowSub = transcriptionModelRow.findViewWithTag("subtitle")
        cloudConfigContainer.addView(transcriptionModelRow)
        root.addView(cloudConfigContainer)

        // Local Models section
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))

        val greedySwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean("greedy_decoding", false)
            isClickable = false
        }
        modelContainer.addView(settingsRow(
            "Greedy decoding",
            "Fewer hallucinations on the larger model (slightly less context)",
            greedySwitch
        ) {
            val newVal = !greedySwitch.isChecked
            prefs().edit().putBoolean("greedy_decoding", newVal).apply()
            greedySwitch.isChecked = newVal
            DictationEngine.reloadModel()
        })

        val vadSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean(LocalTranscriber.VAD_PREF, true)
            isClickable = false
        }
        modelContainer.addView(settingsRow(
            "Voice activity detection",
            "Streaming models only hear speech (Silero VAD): no words invented from noise or silence",
            vadSwitch
        ) {
            val newVal = !vadSwitch.isChecked
            prefs().edit().putBoolean(LocalTranscriber.VAD_PREF, newVal).apply()
            vadSwitch.isChecked = newVal
            DictationEngine.reloadModel()
        })
        root.addView(modelContainer)

        // --- Post-Processing Section ---
        root.addView(sectionHeader("Post-Processing"))
        
        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow("Cleanup transcript", "Uses OpenAI-compatible Chat API to fix grammar and punctuation", postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        root.addView(postProcessRow)

        cleanupConfigContainer = vertical(0)
        val cleanupKeyRow = settingsRow("Cleanup API key", "Tap to set") {
            promptApiKey(OpenAiCompatibleApi.CLEANUP_API_KEY_PREF, "Cleanup API key")
        }
        cleanupKeyRowSub = cleanupKeyRow.findViewWithTag("subtitle")
        cleanupConfigContainer.addView(cleanupKeyRow)

        val cleanupBaseUrlRow = settingsRow("Cleanup API base URL", "Tap to set") {
            promptApiBaseUrl(OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF, "Cleanup API base URL")
        }
        cleanupBaseUrlRowSub = cleanupBaseUrlRow.findViewWithTag("subtitle")
        cleanupConfigContainer.addView(cleanupBaseUrlRow)

        val cleanupModelRow = settingsRow("Cleanup model", OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL) {
            promptApiModel(
                title = "Cleanup model",
                prefKey = OpenAiCompatibleApi.CLEANUP_MODEL_PREF,
                defaultModel = OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL,
                keyPref = OpenAiCompatibleApi.CLEANUP_API_KEY_PREF,
                baseUrlPref = OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF
            )
        }
        cleanupModelRowSub = cleanupModelRow.findViewWithTag("subtitle")
        cleanupConfigContainer.addView(cleanupModelRow)
        root.addView(cleanupConfigContainer)

        promptContainer = vertical(0)
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        root.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(promptRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        
        refresh()
        checkForUpdate()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- App updates (GitHub release builds) ---

    private fun checkForUpdate() {
        val current = AppUpdater.installedVersionCode(this)
        checkingUpdate = true
        updateRowSub.text = "Build $current · checking..."
        Thread {
            val result = runCatching { AppUpdater.check(current) }
            runOnUiThread {
                checkingUpdate = false
                val update = result.getOrNull()
                pendingUpdate = update
                if (result.isSuccess && update == null) AppUpdater.clearDownloads(this)
                updateRowSub.text = when {
                    result.isFailure -> "Build $current · check failed, tap to retry"
                    update != null && AppUpdater.isDownloading ->
                        "Downloading build ${update.versionCode} · tap when it finishes"
                    update != null -> "Build ${update.versionCode} available · tap to install"
                    else -> "Build $current · up to date"
                }
            }
        }.start()
    }

    private fun onUpdateTap() {
        if (checkingUpdate || AppUpdater.isDownloading) return
        val update = pendingUpdate ?: return checkForUpdate()
        if (!AppUpdater.canInstall(this)) {
            // Ask before downloading: granting restarts the app on Android 11+.
            updateRowSub.text = "Allow installs from Phone Whisper, then tap again"
            AppUpdater.openInstallPermissionSettings(this)
            return
        }
        AppUpdater.downloaded(this, update)?.let { AppUpdater.install(this, it); return }

        updateRowSub.text = "Downloading build ${update.versionCode}..."
        Thread {
            var lastMb = -1L
            val result = runCatching {
                AppUpdater.download(this, update) { bytes ->
                    val mb = bytes / 1_000_000
                    if (mb != lastMb) {
                        lastMb = mb
                        runOnUiThread { updateRowSub.text = "Downloading build ${update.versionCode}: $mb MB" }
                    }
                }
            }
            runOnUiThread {
                val apk = result.getOrNull()
                updateRowSub.text = when {
                    result.isFailure -> "Download failed: ${result.exceptionOrNull()?.message} · tap to retry"
                    apk == null -> "Already downloading, tap again when it finishes"
                    else -> "Build ${update.versionCode} downloaded · tap to install"
                }
                // Don't pop the installer over another app or from a recreated (dead) activity.
                if (apk != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    AppUpdater.install(this, apk)
                }
            }
        }.start()
    }

    // --- Model Rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(radio)
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            "${model.quality} · ${model.sizeMb} MB",
            rightContainer
        ) {
            onModelAction(model)
        }
        
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        
        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn
        )
        refreshCard(model)
        
        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return
        if (views.progress.visibility == View.VISIBLE) return // already downloading from this screen

        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

        views.dlBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = "Starting download..."

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }
                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} ready!")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Error: ${state.message}"
                        views.dlBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        DictationEngine.reloadModel()
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        
        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        
        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- Prompt Rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refreshPromptRows(); refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> current != PostProcessor.DEV_PROMPT && current != PostProcessor.SIMPLE_PROMPT
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val hasTranscriptionKey = currentApiKey(OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF).isNotBlank()
        val hasCleanupKey = currentApiKey(OpenAiCompatibleApi.CLEANUP_API_KEY_PREF).isNotBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        val ime = isImeEnabled()
        accRowSub.text = if (acc) "Enabled" else "Optional — tap to enable in settings"
        imeRowSub.text = if (ime) "Enabled — tap to switch keyboards" else "Tap to enable in keyboard settings"

        modelContainer.visibility = View.VISIBLE // buttons can use local models even with the cloud switch on
        cloudConfigContainer.visibility = if (useLocal) View.GONE else View.VISIBLE
        cleanupConfigContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        transcriptionKeyRowSub.text = maskedKey(currentApiKey(OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF))
        transcriptionBaseUrlRowSub.text = currentApiBaseUrl(OpenAiCompatibleApi.TRANSCRIPTION_API_BASE_URL_PREF)
        transcriptionModelRowSub.text = currentApiModel(
            OpenAiCompatibleApi.TRANSCRIPTION_MODEL_PREF,
            OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL
        )
        cleanupKeyRowSub.text = maskedKey(currentApiKey(OpenAiCompatibleApi.CLEANUP_API_KEY_PREF))
        cleanupBaseUrlRowSub.text = currentApiBaseUrl(OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF)
        cleanupModelRowSub.text = currentApiModel(
            OpenAiCompatibleApi.CLEANUP_MODEL_PREF,
            OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL
        )

        val prompt = currentPrompt()
        promptRowSub.text = prompt

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !isLocalModelInstalled(this, cur)) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasTranscriptionKey
        val postReady = !usePostProcessing || hasCleanupKey
        val ready = audio && (acc || ime) && (localReady || cloudReady) && postReady

        statusSubtitle.text = when {
            !ready -> "Setup required"
            ime && acc -> "Ready — use the voice keyboard or the overlay dot"
            ime -> "Ready — switch to the Phone Whisper voice keyboard to dictate"
            else -> "Ready — tap the overlay dot to dictate"
        }
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        
        refreshAllCards()
        refreshPromptRows()
        refreshButtons()
    }

    private fun showImeLog() {
        val text = ImeLog.read(this)
        val view = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Keyboard diagnostics log")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Phone Whisper keyboard log", text))
                toast("Log copied")
            }
            .setNeutralButton("Clear") { _, _ -> ImeLog.clear(this); toast("Log cleared") }
            .setNegativeButton("Close", null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // --- Voice buttons ---

    private fun refreshButtons() {
        buttonsContainer.removeAllViews()
        val list = VoiceButtons.load(this)
        list.forEachIndexed { i, b ->
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(b.color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val title = "${VoiceButtons.colorName(b.color)} — ${if (b.pushToTalk) "hold to talk" else "tap to start / stop"}"
            buttonsContainer.addView(settingsRow(title, buttonSummary(b), dot) { editButton(i) })
        }
        val size = VoiceButtons.sizePercent(this)
        buttonsContainer.addView(settingsRow("Button size", VoiceButtons.sizeName(size)) { chooseSize() })
        if (list.size < VoiceButtons.MAX_BUTTONS) {
            buttonsContainer.addView(settingsRow("Add a button", "Another mic with its own colour and model, e.g. for Chinese") {
                addButton()
            })
        }

        // Stand-alone voice input window for other apps and keyboards (RecognizerIntent)
        buttonsContainer.addView(sectionHeader("Voice input for other apps"))
        val popup = RecognizeActivity.popupButton(this)
        buttonsContainer.addView(settingsRow(
            "Uses the settings of",
            "${VoiceButtons.colorName(popup.color)} button — ${buttonSummary(popup)}"
        ) { choosePopupButton() })
        val silence = RecognizeActivity.popupSilenceMs(this)
        buttonsContainer.addView(settingsRow(
            "Stop after a pause",
            if (silence <= 0) "Off — only the Done button" else "%.1f s of silence after speech".format(silence / 1000f)
        ) { choosePopupSilence() })
    }

    private fun choosePopupButton() {
        val list = VoiceButtons.load(this)
        val labels = list.map { "${VoiceButtons.colorName(it.color)} — ${buttonSummary(it)}" }
        val current = list.indexOfFirst { it.id == RecognizeActivity.popupButton(this).id }
        android.app.AlertDialog.Builder(this)
            .setTitle("Voice input window uses")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                prefs().edit().putString(RecognizeActivity.BUTTON_PREF, list[which].id).apply()
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choosePopupSilence() {
        val values = longArrayOf(0, 1000, 1500, 2000, 3000, 5000)
        val labels = values.map { if (it == 0L) "Off — only the Done button" else "%.1f s".format(it / 1000f) }
        val current = values.indexOf(RecognizeActivity.popupSilenceMs(this)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Stop after a pause")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                prefs().edit().putLong(RecognizeActivity.SILENCE_PREF, values[which]).apply()
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buttonSummary(b: VoiceButton): String {
        val model = VoiceButtons.resolveModel(this, b)
        return when {
            b.model.isNotBlank() && !isLocalModelInstalled(this, b.model) ->
                "${LocalTranscriber.displayName(b.model)} is not installed — using automatic: " +
                    (model?.let { describe(it, b) } ?: "none")
            model == null && b.pushToTalk -> "No local model installed — download one below"
            model == null -> "Cloud transcription"
            b.model.isBlank() -> "Automatic: ${describe(model, b)}"
            else -> describe(model, b)
        }
    }

    /** Model name plus how it runs on this button. */
    private fun describe(model: String, b: VoiceButton): String =
        if (b.pushToTalk) modelWithMode(model) else LocalTranscriber.displayName(model)

    private fun editButton(index: Int) {
        val list = VoiceButtons.load(this).toMutableList()
        val b = list.getOrNull(index) ?: return
        val actions = mutableListOf("Colour", "Mode", "Model")
        if (list.size > 1) actions += "Remove"
        android.app.AlertDialog.Builder(this)
            .setTitle("${VoiceButtons.colorName(b.color)} button")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Colour" -> chooseColor(index)
                    "Mode" -> chooseMode(index)
                    "Model" -> chooseButtonModel(index)
                    "Remove" -> { list.removeAt(index); saveButtons(list) }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addButton() {
        val list = VoiceButtons.load(this).toMutableList()
        val color = VoiceButtons.PALETTE.map { it.second }.firstOrNull { c -> list.none { it.color == c } }
            ?: VoiceButtons.PALETTE.last().second
        list += VoiceButton(VoiceButtons.newId(list), color, pushToTalk = false)
        saveButtons(list)
        chooseButtonModel(list.lastIndex)
    }

    private fun saveButtons(list: List<VoiceButton>) {
        VoiceButtons.save(this, list)
        DictationEngine.reloadModel()
        refresh()
    }

    private fun updateButton(index: Int, change: (VoiceButton) -> VoiceButton) {
        val list = VoiceButtons.load(this).toMutableList()
        if (index !in list.indices) return
        list[index] = change(list[index])
        saveButtons(list)
    }

    private fun chooseColor(index: Int) {
        val current = VoiceButtons.load(this).getOrNull(index) ?: return
        val names = VoiceButtons.PALETTE.map { it.first }
        val checked = VoiceButtons.PALETTE.indexOfFirst { it.second == current.color }
        android.app.AlertDialog.Builder(this)
            .setTitle("Colour")
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                updateButton(index) { it.copy(color = VoiceButtons.PALETTE[which].second) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseMode(index: Int) {
        val current = VoiceButtons.load(this).getOrNull(index) ?: return
        val labels = arrayOf(
            "Tap to start / stop — any model; text after you stop (live with a streaming model)",
            "Hold to talk — a streaming model, or an offline model phrase by phrase",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Mode")
            .setSingleChoiceItems(labels, if (current.pushToTalk) 1 else 0) { dialog, which ->
                updateButton(index) { it.copy(pushToTalk = which == 1) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseButtonModel(index: Int) {
        val current = VoiceButtons.load(this).getOrNull(index) ?: return
        val models = LocalTranscriber.availableModels(this)
            .sortedBy { if (current.pushToTalk && !LocalTranscriber.isStreamingModel(this, it)) 1 else 0 }
        val auto = if (current.pushToTalk) "Automatic (a streaming model if installed)"
        else "Automatic (main model, or the cloud if switched on)"
        val labels = listOf(auto) + models.map { describe(it, current) }
        val values = listOf("") + models
        if (models.isEmpty()) toast("Download a model below to choose it for a button")
        android.app.AlertDialog.Builder(this)
            .setTitle("Model for this button")
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current.model).coerceAtLeast(0)) { dialog, which ->
                updateButton(index) { it.copy(model = values[which]) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseSize() {
        val current = VoiceButtons.sizePercent(this)
        val labels = VoiceButtons.SIZES.map { "${it.first} — ${it.second}%" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Button size")
            .setSingleChoiceItems(labels.toTypedArray(), VoiceButtons.SIZES.indexOfFirst { it.second == current }) { dialog, which ->
                VoiceButtons.setSizePercent(this, VoiceButtons.SIZES[which].second)
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Name — streaming" or "Name — pseudo-streaming (by pauses)". */
    private fun modelWithMode(name: String): String =
        LocalTranscriber.displayName(name) +
            if (LocalTranscriber.isStreamingModel(this, name)) " — streaming" else " — pseudo-streaming (by pauses)"

    private fun promptApiKey(prefKey: String, title: String) {
        val input = EditText(this).apply {
            hint = "API key"
            setText(currentApiKey(prefKey))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString(prefKey, input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptApiBaseUrl(prefKey: String, title: String) {
        val input = EditText(this).apply {
            hint = OpenAiCompatibleApi.DEFAULT_BASE_URL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(currentApiBaseUrl(prefKey))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit()
                    .putString(prefKey, OpenAiCompatibleApi.normalizedBaseUrl(input.text.toString()))
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptApiModel(
        title: String,
        prefKey: String,
        defaultModel: String,
        keyPref: String,
        baseUrlPref: String
    ) {
        val apiKey = currentApiKey(keyPref)
        if (apiKey.isBlank()) {
            toast("Set API key first")
            return
        }

        toast("Loading models...")
        OpenAiCompatibleApi.listModels(apiKey, currentApiBaseUrl(baseUrlPref)) { result ->
            runOnUiThread {
                val models = result.models
                if (models == null) {
                    toast("Models error: ${result.error ?: "unknown"}")
                    return@runOnUiThread
                }

                val current = currentApiModel(prefKey, defaultModel)
                val items = models.toTypedArray()
                val checked = models.indexOf(current)
                android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(items, checked) { dialog, which ->
                        prefs().edit().putString(prefKey, items[which]).apply()
                        dialog.dismiss()
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun currentApiKey(prefKey: String) =
        prefs().getString(prefKey, null)?.takeIf { it.isNotBlank() }
            ?: prefs().getString(OpenAiCompatibleApi.LEGACY_API_KEY_PREF, "") ?: ""
    private fun currentApiBaseUrl(prefKey: String) =
        OpenAiCompatibleApi.normalizedBaseUrl(
            prefs().getString(prefKey, null)?.takeIf { it.isNotBlank() }
                ?: prefs().getString(OpenAiCompatibleApi.LEGACY_API_BASE_URL_PREF, OpenAiCompatibleApi.DEFAULT_BASE_URL)
        )
    private fun currentApiModel(prefKey: String, defaultModel: String) =
        prefs().getString(prefKey, defaultModel)?.ifBlank { defaultModel } ?: defaultModel
    private fun maskedKey(apiKey: String) = when {
        apiKey.isBlank() -> "Tap to set"
        apiKey.length <= 4 -> "***"
        else -> "***${apiKey.takeLast(4)}"
    }

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(
            key = "dev",
            title = "Dev cleanup",
            subtitle = "Best for coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT
        ),
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt()
        )
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun isImeEnabled(): Boolean =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any {
            it.packageName == packageName && it.serviceName == WhisperImeService::class.java.name
        }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}

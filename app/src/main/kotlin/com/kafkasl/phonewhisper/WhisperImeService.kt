package com.kafkasl.phonewhisper

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice keyboard. Types dictated text through the InputConnection, so it needs no
 * accessibility service or overlay. Besides the mic it offers:
 *  - smart punctuation keys that replace the mark the recognizer guessed;
 *  - a strip with the words of the last phrase: tap one to select it, then re-dictate it,
 *    ask the cleanup LLM for alternatives, flip its case, delete it or type it manually;
 *  - word-wise cursor movement and word deletion.
 */
class WhisperImeService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())

    // --- Views ---
    private var stripScroll: HorizontalScrollView? = null
    private var stripRow: LinearLayout? = null
    /** Container of the voice buttons (between the word-left / word-right keys). */
    private var micWrap: LinearLayout? = null
    private var voiceButtons: List<VoiceButton> = emptyList()
    private val buttonViews = LinkedHashMap<String, Pair<FrameLayout, ImageView>>()
    /** Button that started the current utterance. */
    private var activeButton: VoiceButton? = null

    // --- Editor state ---
    private var selStart = -1
    private var selEnd = -1

    /** Last dictated phrase, shown as tappable words. */
    private var phrase: DictatedPhrase? = null
    /** Index of the word selected for fixing, with its absolute range in the editor. */
    /** The word the strip's fix actions apply to: tapped in the strip, selected or under the cursor. */
    private var target: WordTarget? = null
    /** The target is selected (highlighted) in the editor by us. */
    private var targetHighlighted = false
    /** Cursor position before a word was selected, restored after the fix. */
    private var cursorBeforeSelect = -1
    private var variants: List<String>? = null
    private var variantsLoading = false
    private var variantsRequest = 0
    /** Word being re-dictated; null for normal dictation. */
    private var replaceTarget: WordTarget? = null
    /** Selection we just set ourselves, so its onUpdateSelection does not retarget. */
    private var expectedSel: Pair<Int, Int>? = null
    private var expectedSelAt = 0L
    /** Once suggestions were requested, fetch them automatically for each next word. */
    private var autoVariants = false
    private var notice: String? = null
    private var lastKeyWasPunct = false

    // --- Live streaming dictation ---
    /** Text is being typed live while the user speaks (streaming model). */
    private var liveActive = false
    /** Absolute cursor position where the live dictation started. */
    private var liveStart = -1
    /** Everything typed into the editor during this live dictation (segments + punctuation). */
    private val liveText = StringBuilder()
    /** Editor context captured when the current segment started (composing text excluded). */
    private var segBefore: CharSequence? = null
    private var segAfter: CharSequence? = null
    /** Punctuation pressed mid-dictation, applied once the cut segment arrives. */
    private val pendingPunct = mutableListOf<Char>()

    /** A word in the editor at absolute [start, end) with some surrounding text for the LLM. */
    private data class WordTarget(val start: Int, val end: Int, val text: String, val context: String)

    // --- Palette (day / night) ---
    private var night = false
    private val panelBg get() = if (night) 0xFF16181C.toInt() else 0xFFE6E9EF.toInt()
    private val keyBg get() = if (night) 0xFF2A2D33.toInt() else 0xFFFFFFFF.toInt()
    private val keyAltBg get() = if (night) 0xFF22252A.toInt() else 0xFFD3D8E0.toInt()
    private val keyPressed get() = if (night) 0xFF3A3E45.toInt() else 0xFFBFC6D1.toInt()
    private val keyText get() = if (night) 0xFFE8EAED.toInt() else 0xFF1C1F24.toInt()
    private val hintText get() = if (night) 0xFF9AA0A6.toInt() else 0xFF5A6472.toInt()
    private val accent get() = if (night) 0xFF8AB4F8.toInt() else 0xFF1A56DB.toInt()
    private val chipAccentBg get() = if (night) 0xFF1E3A5F.toInt() else 0xFFDCE7FD.toInt()
    private val micRec = 0xFFEF4444.toInt()
    private val micBusy = 0xFF8A9099.toInt()

    private val listener = object : DictationEngine.Listener {
        override fun onStateChanged(state: DictationEngine.State) {
            if (state == DictationEngine.State.IDLE) activeButton = null
            renderMic(state)
            renderStrip()
        }

        override fun onLevel(level: Float) {
            val s = 1f + level * 0.18f
            val active = activeButton?.let { buttonViews[it.id]?.first }
            active?.scaleX = s
            active?.scaleY = s
        }

        override fun onResult(text: String, notice: DictationEngine.Notice) {
            onDictationResult(text, notice)
        }

        override fun onError(message: String) {
            replaceTarget = null
            if (liveActive) finishLive()
            showNotice(message)
        }

        override fun onLivePartial(text: String) = livePartial(text)

        override fun onLiveSegment(text: String, forced: Boolean) = liveSegment(text)
    }

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        ImeLog.init(this)
        ImeLog.add("onCreate")
        DictationEngine.attach(this)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ImeLog.add("onStartInput pkg=${attribute.packageName} restarting=$restarting type=0x${Integer.toHexString(attribute.inputType)}")
        selStart = attribute.initialSelStart
        selEnd = attribute.initialSelEnd
        lastKeyWasPunct = false
        if (!restarting) {
            clearPhrase()
            notice = null
            autoVariants = false
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ImeLog.add("onStartInputView restarting=$restarting")
        // Warm the voice buttons' models while the keyboard is visible; they are freed after idling.
        rebuildMicButtons() // settings may have changed
        for (b in voiceButtons) VoiceButtons.resolveModel(this, b)?.let { DictationEngine.ensureLoaded(it) }
        DictationEngine.scheduleModelRelease()
        renderMic(ownerState())
        renderStrip()
    }

    override fun onFinishInput() {
        ImeLog.add("onFinishInput")
        super.onFinishInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        ImeLog.add("onWindowShown")
    }

    override fun onWindowHidden() {
        ImeLog.add("onWindowHidden")
        super.onWindowHidden()
    }

    override fun onUnbindInput() {
        ImeLog.add("onUnbindInput")
        super.onUnbindInput()
    }

    override fun onDestroy() {
        ImeLog.add("onDestroy")
        super.onDestroy()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        ImeLog.add("onCurrentInputMethodSubtypeChanged mode=${newSubtype?.mode}")
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val r = super.onShowInputRequested(flags, configChange)
        ImeLog.add("onShowInputRequested flags=$flags config=$configChange -> $r")
        return r
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        ImeLog.add("onFinishInputView finishingInput=$finishingInput")
        // Never type a late result into whatever field gets focus next.
        DictationEngine.cancel(listener)
        replaceTarget = null
        if (liveActive) {
            currentInputConnection?.finishComposingText()
            liveActive = false
            pendingPunct.clear()
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (liveActive || replaceTarget != null || ownerState() != DictationEngine.State.IDLE) return
        val expected = expectedSel
        if (expected != null && android.os.SystemClock.uptimeMillis() - expectedSelAt < 1000) {
            // Our own setSelection/commit: ignore intermediate updates until it lands.
            if (expected == (newSelStart to newSelEnd)) expectedSel = null
            return
        }
        expectedSel = null
        retarget()
    }

    /** Point the fix actions at the word under the cursor or the selected word (or nothing). */
    private fun retarget() {
        val ic = currentInputConnection ?: return
        val t = wordAt(ic)
        val cur = target
        if (t != null && cur != null && t.start == cur.start && t.end == cur.end && t.text == cur.text) return
        if (t == null && cur == null) return
        target = t
        targetHighlighted = t != null && selStart != selEnd
        cursorBeforeSelect = -1
        clearVariants()
        if (t != null && autoVariants) requestVariants() else renderStrip()
    }

    /** Word under a collapsed cursor (strictly inside it) or a single selected word. */
    private fun wordAt(ic: InputConnection): WordTarget? {
        if (selStart < 0 || selEnd < 0) return null
        val before = ic.getTextBeforeCursor(120, 0) ?: return null
        val after = ic.getTextAfterCursor(120, 0) ?: return null
        if (selStart == selEnd) {
            val (back, fwd) = TextEditing.wordAroundCursor(before, after) ?: return null
            val word = before.subSequence(before.length - back, before.length).toString() + after.subSequence(0, fwd)
            return WordTarget(selStart - back, selStart + fwd, word, (before.toString() + after).trim())
        }
        val selected = ic.getSelectedText(0)?.toString() ?: return null
        if (!TextEditing.isSingleWord(selected)) return null
        val start = minOf(selStart, selEnd)
        return WordTarget(start, start + selected.length, selected, (before.toString() + selected + after).trim())
    }

    // --- Layout ---

    override fun onCreateInputView(): View {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }

        // Strip: status, word chips, or actions for the selected word.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        stripRow = row
        stripScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // Mic row: word-left · the voice buttons (each with its own colour, mode and model) · word-right
        val micWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        this.micWrap = micWrap
        val micRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(key("←", 1f, alt = true, desc = R.string.ime_word_left).repeating { moveWord(left = true) })
            addView(micWrap, LinearLayout.LayoutParams(0, dp(76), 1.4f))
            addView(key("→", 1f, alt = true, desc = R.string.ime_word_right).repeating { moveWord(left = false) })
        }
        root.addView(micRow)

        // Punctuation row
        val punctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
            for (mark in listOf(',', '.', '?', '!')) {
                addView(key(mark.toString(), 1f).onTap { punctuate(mark) })
            }
            addView(key(getString(R.string.ime_key_delete_word), 1.4f, alt = true, small = true).repeating { deleteWord() })
        }
        root.addView(punctRow)

        // Bottom row: switch keyboard · space · backspace · enter
        val switchKey = iconKey(R.drawable.ic_keyboard, 1f, R.string.ime_switch_keyboard).apply {
            setOnClickListener { it.haptic(); switchToOtherKeyboard() }
            setOnLongClickListener { imm().showInputMethodPicker(); true }
        }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(switchKey)
            addView(key(getString(R.string.ime_key_space), 3f, small = true).spaceBar())
            addView(key("⌫", 1.2f, alt = true, desc = R.string.ime_backspace).repeating { backspace() })
            addView(key("⏎", 1.2f, alt = true, desc = R.string.ime_enter).onTap { enter() })
        }
        root.addView(bottomRow)

        rebuildMicButtons()
        renderMic(ownerState())
        renderStrip()
        return root
    }

    // --- Dictation ---

    private fun ownerState(): DictationEngine.State =
        if (DictationEngine.isOwner(listener)) DictationEngine.state else DictationEngine.State.IDLE

    private fun onMicTap(b: VoiceButton) {
        when (ownerState()) {
            DictationEngine.State.IDLE -> startVoice(b)
            DictationEngine.State.RECORDING -> DictationEngine.stopAndTranscribe()
            DictationEngine.State.TRANSCRIBING -> {}
        }
    }

    /** Start dictating with a voice button, or explain what is missing and open the settings. */
    private fun startVoice(b: VoiceButton): Boolean {
        if (!VoiceButtons.isReady(this, b)) {
            showNotice(getString(R.string.ime_needs_model))
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        activeButton = b
        if (!startDictation(null, b)) {
            activeButton = null
            return false
        }
        return true
    }

    /**
     * Start dictating; with [target] the result replaces that word of the last phrase.
     * [button] picks the model and mode (push-to-talk makes offline models pseudo-stream).
     */
    private fun startDictation(target: WordTarget?, button: VoiceButton? = null): Boolean {
        notice = null
        replaceTarget = target
        // Live typing only for normal dictation; a re-dictated word is replaced in one go.
        val role = if (button?.pushToTalk == true && target == null) DictationEngine.Role.PTT else DictationEngine.Role.TAP
        val model = button?.takeIf { it.model.isNotBlank() }?.let { VoiceButtons.resolveModel(this, it) }
        when (DictationEngine.start(listener, cleanup = target == null, live = target == null, role = role, model = model)) {
            DictationEngine.StartResult.STARTED -> {
                liveActive = DictationEngine.isLive
                if (liveActive) {
                    if (targetHighlighted) deselectWord() else clearTarget()
                    liveStart = minOf(selStart, selEnd)
                    liveText.setLength(0)
                    segBefore = null
                    segAfter = null
                    pendingPunct.clear()
                    lastKeyWasPunct = false
                }
                return true
            }
            DictationEngine.StartResult.NO_PERMISSION -> {
                replaceTarget = null
                showNotice(getString(R.string.ime_grant_mic))
                startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            DictationEngine.StartResult.MIC_ERROR -> {
                replaceTarget = null
                showNotice(getString(R.string.ime_mic_error))
            }
            DictationEngine.StartResult.BUSY -> {
                replaceTarget = null
                showNotice(getString(R.string.ime_busy))
            }
        }
        return false
    }

    private fun onDictationResult(text: String, result: DictationEngine.Notice) {
        if (liveActive) {
            finishLive()
            cleanupLivePhrase()
            return
        }
        val t = replaceTarget
        replaceTarget = null
        if (t != null) {
            val fitted = TextEditing.fitReplacement(text, t.text)
            if (fitted.isNotEmpty()) replaceTargetText(t, fitted, keepSelected = false)
            return
        }
        insertDictation(text)
        when (result) {
            DictationEngine.Notice.NONE -> {}
            DictationEngine.Notice.CLEANUP_NO_KEY -> showNotice(getString(R.string.ime_cleanup_no_key))
            DictationEngine.Notice.CLEANUP_FAILED -> showNotice(getString(R.string.ime_cleanup_failed))
        }
    }

    private fun insertDictation(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val after = ic.getTextAfterCursor(16, 0) ?: ""
        val d = TextEditing.prepareDictation(text, before, after)
        if (d.insert.isEmpty()) { ic.endBatchEdit(); return }
        val start = minOf(selStart, selEnd)
        ic.commitText(d.insert, 1)
        ic.endBatchEdit()

        phrase = DictatedPhrase(d.phrase, if (start >= 0) start + d.phraseOffset else -1)
        resetWordSelection()
        lastKeyWasPunct = false
        notice = null
        renderStrip()
    }

    // --- Word fixing ---

    /** Find where the last phrase currently starts in the editor, or null if it was edited away. */
    private fun locatePhrase(ic: InputConnection, p: DictatedPhrase): Int? {
        val key = p.searchKey
        if (key.isEmpty()) return null
        val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 20000 }, 0)
        val full = extracted?.text?.toString()
        if (full != null) {
            val base = extracted.startOffset
            return TextEditing.findNearest(full, key, p.start - base)?.plus(base)
        }
        // Editors without extracted-text support: search around the cursor.
        val span = key.length + 1000
        val before = ic.getTextBeforeCursor(span, 0)?.toString() ?: ""
        val selected = ic.getSelectedText(0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(span, 0)?.toString() ?: ""
        val cursor = minOf(selStart, selEnd)
        if (cursor < 0) return null
        val base = cursor - before.length
        return TextEditing.findNearest(before + selected + after, key, p.start - base)?.plus(base)
    }

    private fun phraseLost() {
        clearPhrase()
        showNotice(getString(R.string.ime_text_changed))
    }

    /** Our own selection change: its onUpdateSelection must not retarget. */
    private fun expectSelection(ic: InputConnection, start: Int, end: Int) {
        expectedSel = start to end
        expectedSelAt = android.os.SystemClock.uptimeMillis()
        ic.setSelection(start, end)
    }

    /** Tap on a word of the last phrase: select it in the editor and show its actions. */
    private fun selectPhraseWord(index: Int) {
        if (liveActive) return
        val ic = currentInputConnection ?: return
        val p = phrase ?: return
        val base = locatePhrase(ic, p) ?: return phraseLost()
        val located = p.withStart(base)
        phrase = located
        val w = located.words.getOrNull(index) ?: return
        if (!targetHighlighted) cursorBeforeSelect = maxOf(selStart, selEnd)
        target = WordTarget(base + w.start, base + w.end, w.text, located.text)
        targetHighlighted = true
        clearVariants()
        notice = null
        lastKeyWasPunct = false
        expectSelection(ic, base + w.start, base + w.end)
        if (autoVariants) requestVariants() else renderStrip()
    }

    /** True if the editor still holds [t]'s text at its range (unknown editors pass). */
    private fun stillThere(ic: InputConnection, t: WordTarget): Boolean {
        val ex = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 20000 }, 0)
        val full = ex?.text ?: return true
        val s = t.start - ex.startOffset
        val e = t.end - ex.startOffset
        if (s < 0 || e > full.length) return true
        return full.subSequence(s, e).toString() == t.text
    }

    /**
     * Replace the word [t] with [replacement] (empty = delete it with one adjacent space).
     * Afterwards the cursor returns to where it was before the word was tapped (or after
     * the new word), or the new word stays selected with [keepSelected].
     */
    private fun replaceTargetText(t: WordTarget, replacement: String, keepSelected: Boolean) {
        val ic = currentInputConnection ?: return
        if (!stillThere(ic, t)) {
            clearTarget()
            showNotice(getString(R.string.ime_text_changed))
            return
        }
        var s = t.start
        var e = t.end
        if (replacement.isEmpty()) {
            val ex = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 20000 }, 0)
            val full = ex?.text
            if (full != null) {
                val r = TextEditing.deletionExtent(full, s - ex.startOffset, e - ex.startOffset)
                s = r.first + ex.startOffset
                e = r.last + 1 + ex.startOffset
            }
        }
        val delta = replacement.length - (e - s)
        val saved = cursorBeforeSelect

        ic.beginBatchEdit()
        ic.setSelection(s, e)
        ic.commitText(replacement, 1)
        if (keepSelected && replacement.isNotEmpty()) {
            expectSelection(ic, s, s + replacement.length)
        } else {
            val cursor = when {
                saved < 0 -> s + replacement.length
                saved >= e -> saved + delta
                saved > s -> s + replacement.length
                else -> saved
            }
            expectSelection(ic, cursor, cursor)
        }
        ic.endBatchEdit()

        adjustPhrase(s, e, replacement)
        if (keepSelected && replacement.isNotEmpty()) {
            target = WordTarget(s, s + replacement.length, replacement, t.context.replace(t.text, replacement))
            targetHighlighted = true
            clearVariants()
            if (autoVariants) requestVariants()
        } else {
            clearTarget()
        }
        renderStrip()
    }

    /** Keep the last-phrase model in sync after the editor range [s, e) became [replacement]. */
    private fun adjustPhrase(s: Int, e: Int, replacement: String) {
        val p = phrase ?: return
        if (p.start < 0) return
        val pEnd = p.start + p.text.length
        val delta = replacement.length - (e - s)
        phrase = when {
            e <= p.start -> p.withStart(p.start + delta)
            s >= pEnd -> p
            s >= p.start && e <= pEnd ->
                DictatedPhrase(p.text.replaceRange(s - p.start, e - p.start, replacement).trim(), p.start)
                    .takeIf { it.words.isNotEmpty() }
            else -> null
        }
    }

    /** ✕: drop the actions; a word we highlighted is unselected and the cursor restored. */
    private fun deselectWord() {
        val ic = currentInputConnection
        val saved = cursorBeforeSelect
        val wasHighlighted = targetHighlighted
        val t = target
        clearTarget()
        if (ic != null && wasHighlighted) {
            val pos = if (saved >= 0) saved else t?.end ?: -1
            if (pos >= 0) expectSelection(ic, pos, pos)
        }
        renderStrip()
    }

    private fun requestVariants() {
        val t = target ?: return
        autoVariants = true
        val id = ++variantsRequest
        variantsLoading = true
        renderStrip()
        WordAlternatives.request(this, t.context, t.text) { list, error ->
            handler.post {
                if (id != variantsRequest) return@post
                variantsLoading = false
                when {
                    list == null && error == null -> { notice = getString(R.string.ime_variants_need_key); autoVariants = false }
                    list == null -> notice = getString(R.string.ime_variants_error, error)
                    list.isEmpty() -> notice = getString(R.string.ime_variants_none)
                    else -> variants = list.map { TextEditing.fitReplacement(it, t.text) }.filter { it.isNotEmpty() }
                }
                renderStrip()
            }
        }
    }

    /** "Type": select the word so the regular keyboard types over it, then switch. */
    private fun typeOverTarget() {
        val ic = currentInputConnection
        val t = target
        if (ic != null && t != null && !targetHighlighted) expectSelection(ic, t.start, t.end)
        switchToOtherKeyboard()
    }

    /**
     * Back to the keyboard we came from (e.g. after HeliBoard's mic key opened us as its
     * voice input), else the next one, else the system picker.
     */
    private fun switchToOtherKeyboard() {
        val prev = switchToPreviousInputMethod()
        ImeLog.add("key: switchToPreviousInputMethod -> $prev")
        if (prev) return
        val switched = try { switchToNextInputMethod(false) } catch (_: Exception) { false }
        ImeLog.add("key: switchToNextInputMethod -> $switched")
        if (!switched) imm().showInputMethodPicker()
    }

    private fun clearVariants() {
        variants = null
        variantsLoading = false
        variantsRequest++
    }

    private fun clearTarget() {
        target = null
        targetHighlighted = false
        cursorBeforeSelect = -1
        clearVariants()
    }

    private fun resetWordSelection() = clearTarget()

    private fun clearPhrase() {
        phrase = null
        resetWordSelection()
    }

    // --- Keys ---

    private fun punctuate(mark: Char) {
        if (liveActive) {
            // Finalize what is being said right now, then place the mark after it.
            pendingPunct.add(mark)
            // While the tail is still decoding, the final segment will flush the marks.
            if (!DictationEngine.cutSegment() && ownerState() != DictationEngine.State.TRANSCRIBING) {
                flushPendingPunct()
            }
            return
        }
        if (targetHighlighted) deselectWord()
        notice = null
        applyPunct(mark)
        renderStrip()
    }

    private fun applyPunct(mark: Char) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(32, 0) ?: ""
        val after = ic.getTextAfterCursor(1, 0) ?: ""
        val edit = TextEditing.punctuate(before, mark, after, append = lastKeyWasPunct)
        if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
        ic.commitText(edit.insert, 1)
        ic.endBatchEdit()
        lastKeyWasPunct = true
        if (liveActive) {
            liveText.setLength(maxOf(0, liveText.length - edit.deleteBefore))
            liveText.append(edit.insert)
        }
    }

    private fun flushPendingPunct() {
        val marks = pendingPunct.toList()
        pendingPunct.clear()
        marks.forEach { applyPunct(it) }
    }

    // --- Live streaming ---

    private fun captureSegmentContext(ic: InputConnection) {
        if (segBefore != null) return
        segBefore = ic.getTextBeforeCursor(64, 0) ?: ""
        segAfter = ic.getTextAfterCursor(16, 0) ?: ""
    }

    /** Show the unfinished segment as composing (underlined) text; it may still change. */
    private fun livePartial(text: String) {
        if (!liveActive) return
        val ic = currentInputConnection ?: return
        captureSegmentContext(ic)
        val d = TextEditing.prepareDictation(text, segBefore!!, segAfter!!)
        ic.setComposingText(d.insert, 1)
    }

    /** Commit a finished segment in place of the composing text, then any pending punctuation. */
    private fun liveSegment(text: String) {
        if (!liveActive) return
        val ic = currentInputConnection ?: return
        captureSegmentContext(ic)
        val d = TextEditing.prepareDictation(text, segBefore!!, segAfter!!)
        ic.commitText(d.insert, 1)
        liveText.append(d.insert)
        segBefore = null
        segAfter = null
        if (d.insert.isNotEmpty()) lastKeyWasPunct = false
        flushPendingPunct()
    }

    /** Close the live dictation and expose what was typed as the fixable phrase. */
    private fun finishLive() {
        liveActive = false
        currentInputConnection?.finishComposingText()
        flushPendingPunct()
        val typed = liveText.toString()
        val body = typed.trim()
        val lead = typed.length - typed.trimStart().length
        phrase = if (body.isNotEmpty()) DictatedPhrase(body, if (liveStart >= 0) liveStart + lead else -1) else null
        resetWordSelection()
        segBefore = null
        segAfter = null
        renderStrip()
    }

    /** With cleanup enabled, replace the live-typed phrase by its cleaned version. */
    private fun cleanupLivePhrase() {
        val p = phrase ?: return
        if (!DictationEngine.isCleanupEnabled()) return
        showNotice(getString(R.string.ime_cleaning))
        DictationEngine.cleanup(p.text) { cleaned, result ->
            handler.post {
                notice = null
                when (result) {
                    DictationEngine.Notice.CLEANUP_NO_KEY -> notice = getString(R.string.ime_cleanup_no_key)
                    DictationEngine.Notice.CLEANUP_FAILED -> notice = getString(R.string.ime_cleanup_failed)
                    DictationEngine.Notice.NONE -> if (phrase == p && cleaned.trim() != p.text) replacePhrase(p, cleaned.trim())
                }
                renderStrip()
            }
        }
    }

    private fun replacePhrase(p: DictatedPhrase, text: String) {
        val ic = currentInputConnection ?: return
        if (ownerState() != DictationEngine.State.IDLE) return
        val base = locatePhrase(ic, p) ?: return
        ic.beginBatchEdit()
        ic.setSelection(base, base + p.text.length)
        val current = ic.getSelectedText(0)?.toString()
        if (current == p.text) {
            ic.commitText(text, 1)
            phrase = DictatedPhrase(text, base)
        } else {
            val end = maxOf(selStart, selEnd)
            ic.setSelection(end, end)
        }
        ic.endBatchEdit()
    }

    /** Editing keys during live dictation stop it first (like Gboard); the key itself is dropped. */
    private fun stopLiveForKey(): Boolean {
        if (!liveActive || ownerState() != DictationEngine.State.RECORDING) return false
        DictationEngine.stopAndTranscribe()
        return true
    }

    private fun commitPlain(text: String) {
        if (stopLiveForKey()) return
        lastKeyWasPunct = false
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        if (stopLiveForKey()) return
        val ic = currentInputConnection ?: return
        lastKeyWasPunct = false
        if (selStart != selEnd && selStart >= 0) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun deleteWord() {
        if (stopLiveForKey()) return
        val ic = currentInputConnection ?: return
        lastKeyWasPunct = false
        if (selStart != selEnd && selStart >= 0) { ic.commitText("", 1); return }
        val n = TextEditing.deleteWordLength(ic.getTextBeforeCursor(64, 0) ?: "")
        if (n > 0) ic.deleteSurroundingText(n, 0)
    }

    private fun moveWord(left: Boolean) {
        if (stopLiveForKey()) return
        val ic = currentInputConnection ?: return
        lastKeyWasPunct = false
        if (selStart < 0 || selEnd < 0) {
            sendDownUpKeyEvents(if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val pos = if (left) {
            val from = minOf(selStart, selEnd)
            from - TextEditing.previousWordOffset(ic.getTextBeforeCursor(64, 0) ?: "")
        } else {
            val from = maxOf(selStart, selEnd)
            from + TextEditing.nextWordOffset(ic.getTextAfterCursor(64, 0) ?: "")
        }
        ic.setSelection(pos, pos)
    }

    /**
     * Move the cursor one character from absolute position [from]. Steps over surrogate
     * pairs (emoji) as a whole. Returns the new position, or null at the edge of the text.
     */
    private fun stepCursor(ic: InputConnection, from: Int, forward: Boolean): Int? {
        val n = if (forward) TextEditing.charStepForward(ic.getTextAfterCursor(2, 0) ?: "")
        else TextEditing.charStepBackward(ic.getTextBeforeCursor(2, 0) ?: "")
        if (n == 0) return null
        val to = if (forward) from + n else from - n
        ic.setSelection(to, to)
        return to
    }

    private fun enter() {
        if (stopLiveForKey()) return
        lastKeyWasPunct = false
        // Performs the field's action (send, search, …) when it has one, otherwise a newline.
        sendKeyChar('\n')
    }

    // --- Rendering ---

    /** (Re)create the voice buttons from the settings: colour, mode, model, size. */
    private fun rebuildMicButtons() {
        val wrap = micWrap ?: return
        wrap.removeAllViews()
        buttonViews.clear()
        voiceButtons = VoiceButtons.load(this)
        val n = voiceButtons.size
        // The default size (74 %) keeps the original 68 dp; more buttons share the row.
        val wanted = dp(92) * VoiceButtons.sizePercent(this) / 100
        val fit = when {
            n <= 2 -> dp(76)
            n == 3 -> dp(60)
            else -> dp(48)
        }
        val size = minOf(wanted, fit)
        (wrap.layoutParams as? LinearLayout.LayoutParams)?.weight = 1.4f + 0.8f * (n - 1).coerceAtLeast(0)
        for (b in voiceButtons) {
            val (circle, icon) = micButton(b.color, if (b.pushToTalk) R.string.ime_ptt else R.string.ime_mic, size)
            if (b.pushToTalk) circle.pushToTalk(b) else circle.setOnClickListener { it.haptic(); onMicTap(b) }
            wrap.addView(circle, LinearLayout.LayoutParams(size, size).apply { setMargins(dp(4), 0, dp(4), 0) })
            buttonViews[b.id] = circle to icon
        }
        renderMic(ownerState())
    }

    private fun renderMic(state: DictationEngine.State) {
        for (b in voiceButtons) {
            val (circle, icon) = buttonViews[b.id] ?: continue
            renderMicButton(circle, icon, b.color, state, active = activeButton?.id == b.id)
        }
    }

    /** The button that started the recording turns red / grey; the others stay idle. */
    private fun renderMicButton(circle: FrameLayout, icon: ImageView, idle: Int, state: DictationEngine.State, active: Boolean) {
        val color = when {
            !active || state == DictationEngine.State.IDLE -> idle
            state == DictationEngine.State.RECORDING -> micRec
            else -> micBusy
        }
        circle.background = oval(color)
        icon.alpha = if (active && state == DictationEngine.State.TRANSCRIBING) 0.5f else 1f
        if (!active || state != DictationEngine.State.RECORDING) {
            circle.scaleX = 1f
            circle.scaleY = 1f
        }
    }

    private fun micButton(color: Int, desc: Int, size: Int): Pair<FrameLayout, ImageView> {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = size / 5; setPadding(p, p, p, p)
        }
        val circle = FrameLayout(this).apply {
            background = oval(color)
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isClickable = true
            contentDescription = getString(desc)
        }
        return circle to icon
    }

    /** Push-to-talk: record while held, transcribe on release. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pushToTalk(b: VoiceButton) {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.haptic()
                    if (ownerState() == DictationEngine.State.IDLE) {
                        startVoice(b)
                        renderMic(ownerState())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeButton?.id == b.id && ownerState() == DictationEngine.State.RECORDING) {
                        v.haptic()
                        DictationEngine.stopAndTranscribe()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun showNotice(message: String) {
        notice = message
        renderStrip()
    }

    private fun renderStrip() {
        val row = stripRow ?: return
        row.removeAllViews()
        val p = phrase
        when (ownerState()) {
            DictationEngine.State.RECORDING -> {
                val redo = replaceTarget?.text
                row.addView(label(
                    if (redo != null) getString(R.string.ime_redictate_recording, redo)
                    else getString(R.string.ime_recording)
                ))
            }
            DictationEngine.State.TRANSCRIBING -> row.addView(label(getString(R.string.ime_transcribing)))
            DictationEngine.State.IDLE -> {
                notice?.let { row.addView(label(it, highlight = true)) }
                val t = target
                if (t != null) {
                    row.addView(label("«${t.text}»", bold = true))
                    variants?.forEach { v -> row.addView(chip(v, primary = true) { replaceTargetText(t, v, keepSelected = false) }) }
                    if (variants == null) {
                        val text = if (variantsLoading) getString(R.string.ime_variants_loading) else getString(R.string.ime_action_variants)
                        row.addView(chip(text, primary = true) { if (!variantsLoading) requestVariants() })
                    }
                    row.addView(chip(getString(R.string.ime_action_redictate)) { startDictation(t) })
                    row.addView(chip("Aa") { replaceTargetText(t, TextEditing.toggleFirstLetterCase(t.text), keepSelected = true) })
                    row.addView(chip(getString(R.string.ime_action_delete)) { replaceTargetText(t, "", keepSelected = false) })
                    row.addView(chip(getString(R.string.ime_action_type)) { typeOverTarget() })
                    row.addView(chip("✕") { deselectWord() })
                } else if (p != null && p.words.isNotEmpty()) {
                    p.words.forEachIndexed { i, w -> row.addView(chip(w.text) { selectPhraseWord(i) }) }
                    row.addView(chip("✕") { clearPhrase(); notice = null; renderStrip() })
                } else if (notice == null) {
                    row.addView(label(getString(R.string.ime_hint)))
                }
            }
        }
        stripScroll?.post { stripScroll?.scrollTo(0, 0) }
    }

    // --- View helpers ---

    private fun label(text: String, highlight: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(if (highlight) accent else if (bold) keyText else hintText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun chip(text: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(if (primary) accent else keyText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12), 0, dp(12), 0)
        background = pressable(if (primary) chipAccentBg else keyBg, radius = 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        isClickable = true
        setOnClickListener { it.haptic(); onClick() }
    }

    private fun key(text: String, weight: Float, alt: Boolean = false, small: Boolean = false, desc: Int? = null) =
        TextView(this).apply {
            this.text = text
            setTextColor(keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 15f else 20f)
            gravity = Gravity.CENTER
            maxLines = 1
            background = pressable(if (alt) keyAltBg else keyBg, radius = 10)
            desc?.let { contentDescription = getString(it) }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) }
            isClickable = true
        }

    private fun iconKey(res: Int, weight: Float, desc: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(keyText)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = pressable(keyAltBg, radius = 10)
        contentDescription = getString(desc)
        val p = dp(12); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) }
        isClickable = true
    }

    private fun <T : View> T.onTap(action: () -> Unit): T = apply {
        setOnClickListener { it.haptic(); action() }
    }

    /** Fire on press and keep repeating while held (backspace, word moves). */
    /**
     * Space bar: a tap types a space; sliding the finger left/right moves the cursor one
     * character per step, like HeliBoard / Gboard.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun <T : View> T.spaceBar(): T = apply {
        val startThreshold = dp(14).toFloat()
        val stepWidth = dp(9).toFloat()
        var downX = 0f
        var anchorX = 0f
        var swiping = false
        var cursor = -1
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.haptic()
                    downX = ev.x
                    swiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiping && liveActive) return@setOnTouchListener true
                    if (!swiping && kotlin.math.abs(ev.x - downX) > startThreshold) {
                        swiping = true
                        anchorX = ev.x
                        lastKeyWasPunct = false
                        // Collapse any selection towards the swipe direction.
                        cursor = if (ev.x < downX) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
                        if (selStart != selEnd && cursor >= 0) currentInputConnection?.setSelection(cursor, cursor)
                    }
                    if (swiping) {
                        val ic = currentInputConnection
                        var steps = ((ev.x - anchorX) / stepWidth).toInt()
                        if (ic != null && steps != 0) {
                            anchorX += steps * stepWidth
                            val forward = steps > 0
                            var moved = false
                            while (steps != 0) {
                                if (cursor < 0) {
                                    // Position unknown to the IME: fall back to arrow keys.
                                    sendDownUpKeyEvents(if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                                    moved = true
                                } else {
                                    val next = stepCursor(ic, cursor, forward) ?: break
                                    cursor = next
                                    moved = true
                                }
                                steps += if (forward) -1 else 1
                            }
                            if (moved) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!swiping) commitPlain(" ")
                    swiping = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    swiping = false
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun <T : View> T.repeating(action: () -> Unit): T = apply {
        val repeat = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 70) }
        }
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.haptic()
                    action()
                    handler.postDelayed(repeat, 420)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    true
                }
                else -> true
            }
        }
    }

    private fun View.haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun pressable(color: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(keyPressed, radius))
        addState(intArrayOf(), roundRect(color, radius))
    }

    private fun roundRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

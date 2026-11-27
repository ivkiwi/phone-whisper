package com.kafkasl.phonewhisper

/**
 * Messenger protocol between Phone Whisper (the transcription engine) and a keyboard
 * that embeds its voice buttons (the HeliBoard fork). Keep this file dependency-free:
 * the client copies it verbatim.
 *
 * The keyboard records the microphone itself (an app in the background may not) and
 * streams 16 kHz mono 16-bit little-endian PCM to the engine; the engine answers with
 * live partial/segment text and the final result. All requests carry `replyTo`.
 *
 * Binding: Intent(ACTION_BIND) resolved with PackageManager.queryIntentServices; the
 * client needs `<uses-permission android:name="PERMISSION">` (granted by the user at
 * runtime) and a `<queries>` entry for ACTION_BIND.
 */
object RemoteProtocol {
    const val VERSION = 2
    const val ACTION_BIND = "com.kafkasl.phonewhisper.action.BIND_DICTATION"
    const val PERMISSION = "com.kafkasl.phonewhisper.permission.DICTATION"

    // --- Client → engine ---
    /** Keyboard became visible: warm up the local model. */
    const val MSG_PREPARE = 1
    /** Start an utterance. Bundle: KEY_LIVE, KEY_CLEANUP, KEY_ROLE. Answer: MSG_STARTED or MSG_ERROR. */
    const val MSG_START = 2
    /** Audio chunk. Bundle: KEY_PCM (ByteArray). */
    const val MSG_AUDIO = 3
    /** End of audio: transcribe. Answer: MSG_RESULT or MSG_ERROR. */
    const val MSG_STOP = 4
    /** Abort; no result. */
    const val MSG_CANCEL = 5
    /** Live mode: finalize the current segment now. Answer: MSG_SEGMENT with KEY_FORCED. */
    const val MSG_CUT = 6
    /** Clean up text with the configured LLM. Bundle: KEY_TEXT, KEY_ID. Answer: MSG_CLEANED. */
    const val MSG_CLEANUP = 7
    /** Suggest replacements. Bundle: KEY_TEXT (context), KEY_WORD, KEY_ID. Answer: MSG_VARIANTS. */
    const val MSG_VARIANTS = 8
    /** Ask for engine info. Answer: MSG_INFO. */
    const val MSG_INFO = 9

    // --- Engine → client ---
    /** Bundle: KEY_LIVE (true if text will stream live). */
    const val MSG_STARTED = 101
    /** Bundle: KEY_STATE ("IDLE" | "RECORDING" | "TRANSCRIBING"). */
    const val MSG_STATE = 102
    /** Live: current unfinished segment. Bundle: KEY_TEXT. */
    const val MSG_PARTIAL = 103
    /** Live: finalized segment. Bundle: KEY_TEXT, KEY_FORCED. */
    const val MSG_SEGMENT = 104
    /** Final text (in live mode: everything, already delivered as segments). Bundle: KEY_TEXT, KEY_NOTICE. */
    const val MSG_RESULT = 105
    /** Bundle: KEY_TEXT (message), KEY_ERROR_CODE. */
    const val MSG_ERROR = 106
    /** Bundle: KEY_ID, KEY_TEXT, KEY_NOTICE. */
    const val MSG_CLEANED = 107
    /** Bundle: KEY_ID, KEY_LIST (String array) or KEY_TEXT (error); KEY_ERROR_CODE "no_key" without a key. */
    const val MSG_VARIANTS_RESULT = 108
    /** Bundle: KEY_VERSION, KEY_STREAMING (push-to-talk available), KEY_MIC_BUTTONS, KEY_CLEANUP. */
    const val MSG_INFO_RESULT = 109

    const val KEY_LIVE = "live"
    const val KEY_CLEANUP = "cleanup"
    const val KEY_PCM = "pcm"
    const val KEY_TEXT = "text"
    const val KEY_WORD = "word"
    const val KEY_ID = "id"
    const val KEY_FORCED = "forced"
    const val KEY_NOTICE = "notice"
    const val KEY_STATE = "state"
    const val KEY_LIST = "list"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_VERSION = "version"
    const val KEY_STREAMING = "streaming"
    const val KEY_MIC_BUTTONS = "mic_buttons"
    /** Which mic: ROLE_TAP (its own model choice) or ROLE_PTT (streaming / pseudo-streaming model). */
    const val KEY_ROLE = "role"
    const val ROLE_TAP = "tap"
    const val ROLE_PTT = "ptt"

    const val ERROR_BUSY = "busy"
    const val ERROR_NO_KEY = "no_key"
    const val ERROR_TRANSCRIPTION = "transcription"

    /** Notices: "none" | "cleanup_no_key" | "cleanup_failed". */
    const val NOTICE_NONE = "none"
    const val NOTICE_CLEANUP_NO_KEY = "cleanup_no_key"
    const val NOTICE_CLEANUP_FAILED = "cleanup_failed"

    const val SAMPLE_RATE = 16000
}

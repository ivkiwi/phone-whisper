package com.kafkasl.phonewhisper

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.kafkasl.phonewhisper.RemoteProtocol as P

/**
 * Lets another keyboard (the HeliBoard fork) use this app's dictation engine: it streams
 * microphone audio here and receives live/final text, cleanup and word suggestions.
 * Guarded by the dangerous permission [RemoteProtocol.PERMISSION], which the user grants
 * to the keyboard. See [RemoteProtocol].
 */
class RemoteDictationService : Service() {

    private var client: Messenger? = null

    /** Cancels an external utterance whose client stopped sending audio (stalled or gone). */
    private val audioWatchdog = Runnable {
        if (DictationEngine.isOwner(listener) && DictationEngine.state == DictationEngine.State.RECORDING) {
            ImeLog.add("remote: no audio for ${AUDIO_IDLE_MS / 1000}s, cancelling")
            DictationEngine.cancel(listener)
        }
    }

    private fun armWatchdog() {
        handler.removeCallbacks(audioWatchdog)
        handler.postDelayed(audioWatchdog, AUDIO_IDLE_MS)
    }

    private val listener = object : DictationEngine.Listener {
        override fun onStateChanged(state: DictationEngine.State) =
            send(P.MSG_STATE) { putString(P.KEY_STATE, state.name) }

        override fun onResult(text: String, notice: DictationEngine.Notice) =
            send(P.MSG_RESULT) { putString(P.KEY_TEXT, text); putString(P.KEY_NOTICE, notice.wire()) }

        override fun onError(message: String) =
            send(P.MSG_ERROR) { putString(P.KEY_TEXT, message); putString(P.KEY_ERROR_CODE, P.ERROR_TRANSCRIPTION) }

        override fun onLivePartial(text: String) = send(P.MSG_PARTIAL) { putString(P.KEY_TEXT, text) }

        override fun onLiveSegment(text: String, forced: Boolean) =
            send(P.MSG_SEGMENT) { putString(P.KEY_TEXT, text); putBoolean(P.KEY_FORCED, forced) }
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            msg.replyTo?.let { client = it }
            val data = msg.data ?: Bundle.EMPTY
            when (msg.what) {
                P.MSG_PREPARE -> {
                    DictationEngine.ensureModelLoaded(DictationEngine.Role.TAP, DictationEngine.Role.PTT)
                    DictationEngine.scheduleModelRelease()
                }
                P.MSG_START -> {
                    val result = DictationEngine.startExternal(
                        listener,
                        cleanup = data.getBoolean(P.KEY_CLEANUP, true),
                        live = data.getBoolean(P.KEY_LIVE, false),
                        role = if (data.getString(P.KEY_ROLE) == P.ROLE_PTT) DictationEngine.Role.PTT else DictationEngine.Role.TAP,
                    )
                    if (result == DictationEngine.StartResult.STARTED) {
                        armWatchdog()
                        send(P.MSG_STARTED) { putBoolean(P.KEY_LIVE, DictationEngine.isLive) }
                    } else {
                        send(P.MSG_ERROR) {
                            putString(P.KEY_TEXT, "Dictation is already running")
                            putString(P.KEY_ERROR_CODE, P.ERROR_BUSY)
                        }
                    }
                }
                P.MSG_AUDIO -> if (DictationEngine.isOwner(listener)) {
                    armWatchdog()
                    data.getByteArray(P.KEY_PCM)?.let { DictationEngine.feedExternal(it) }
                }
                P.MSG_STOP -> if (DictationEngine.isOwner(listener)) DictationEngine.stopAndTranscribe()
                P.MSG_CANCEL -> DictationEngine.cancel(listener)
                P.MSG_CUT -> if (DictationEngine.isOwner(listener)) DictationEngine.cutSegment()
                P.MSG_CLEANUP -> {
                    val id = data.getInt(P.KEY_ID)
                    val reply = msg.replyTo
                    DictationEngine.cleanup(data.getString(P.KEY_TEXT).orEmpty()) { text, notice ->
                        post {
                            send(P.MSG_CLEANED, reply) {
                                putInt(P.KEY_ID, id); putString(P.KEY_TEXT, text); putString(P.KEY_NOTICE, notice.wire())
                            }
                        }
                    }
                }
                P.MSG_VARIANTS -> {
                    val id = data.getInt(P.KEY_ID)
                    val reply = msg.replyTo
                    WordAlternatives.request(
                        this@RemoteDictationService,
                        data.getString(P.KEY_TEXT).orEmpty(),
                        data.getString(P.KEY_WORD).orEmpty(),
                    ) { list, error ->
                        post {
                            send(P.MSG_VARIANTS_RESULT, reply) {
                                putInt(P.KEY_ID, id)
                                when {
                                    list != null -> putStringArray(P.KEY_LIST, list.toTypedArray())
                                    error == null -> putString(P.KEY_ERROR_CODE, P.ERROR_NO_KEY)
                                    else -> putString(P.KEY_TEXT, error)
                                }
                            }
                        }
                    }
                }
                P.MSG_INFO -> send(P.MSG_INFO_RESULT, msg.replyTo) {
                    val prefs = getSharedPreferences("phonewhisper", MODE_PRIVATE)
                    putInt(P.KEY_VERSION, P.VERSION)
                    putBoolean(P.KEY_STREAMING, LocalTranscriber.isPushToTalkAvailable(this@RemoteDictationService))
                    putString(P.KEY_MIC_BUTTONS, "both")
                    putBoolean(P.KEY_CLEANUP, DictationEngine.isCleanupEnabled())
                }
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        DictationEngine.attach(this)
        ImeLog.init(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        ImeLog.add("remote: bind")
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ImeLog.add("remote: unbind")
        DictationEngine.cancel(listener)
        client = null
        return false
    }

    private fun send(what: Int, to: Messenger? = client, fill: Bundle.() -> Unit) {
        val target = to ?: return
        val msg = Message.obtain(null, what).apply { data = Bundle().apply(fill) }
        try {
            target.send(msg)
        } catch (_: RemoteException) {
            // Client died; the unbind will clean up.
        }
    }

    private companion object {
        const val AUDIO_IDLE_MS = 10_000L
    }

    private fun DictationEngine.Notice.wire(): String = when (this) {
        DictationEngine.Notice.NONE -> P.NOTICE_NONE
        DictationEngine.Notice.CLEANUP_NO_KEY -> P.NOTICE_CLEANUP_NO_KEY
        DictationEngine.Notice.CLEANUP_FAILED -> P.NOTICE_CLEANUP_FAILED
    }
}

package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent event log for the voice keyboard (last [MAX_LINES] lines in
 * files/ime_events.log), so lifecycle problems on a real phone can be read and copied
 * from the app without adb. Also records uncaught exceptions of the process.
 */
object ImeLog {
    private const val MAX_LINES = 300
    private const val FILE = "ime_events.log"
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE)
        file = f
        if (f.exists()) runCatching { f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            add("CRASH in ${t.name}: ${Log.getStackTraceString(e).lines().take(15).joinToString(" | ")}")
            previous?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun add(event: String) {
        val line = "${time.format(Date())} $event"
        Log.i("ImeLog", line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        file?.let { f -> runCatching { f.writeText(lines.joinToString("\n", postfix = "\n")) } }
    }

    @Synchronized
    fun read(context: Context): String {
        init(context)
        return lines.joinToString("\n").ifBlank { "(empty)" }
    }

    @Synchronized
    fun clear(context: Context) {
        init(context)
        lines.clear()
        file?.delete()
    }
}

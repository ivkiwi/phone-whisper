// SPDX-License-Identifier: MIT
package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * One mic button in the suggestion strip: its colour, whether it is push-to-talk, and its
 * model ("" = automatic: the main model for tap buttons, a streaming model for push-to-talk).
 * Extra buttons let e.g. a Chinese model sit next to the Russian ones.
 */
data class VoiceButton(
    val id: String,
    val color: Int,
    val pushToTalk: Boolean,
    val model: String = "",
)

object VoiceButtons {
    const val PREF = "voice_buttons"
    const val MAX_BUTTONS = 5
    const val SIZE_PREF = "voice_button_size"
    /** Button diameter in percent of the suggestion strip height. */
    val SIZES: List<Pair<String, Int>> = listOf(
        "Extra small" to 50, "Small" to 62, "Medium" to 74, "Large" to 86, "Full height" to 100,
    )
    const val DEFAULT_SIZE = 74

    fun sizePercent(context: Context): Int =
        prefs(context).getInt(SIZE_PREF, DEFAULT_SIZE).coerceIn(30, 100)

    fun setSizePercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(SIZE_PREF, percent).apply()
    }

    fun sizeName(percent: Int): String = SIZES.firstOrNull { it.second == percent }?.first ?: "$percent%"

    /** Colours offered for buttons (name to ARGB). Red and grey are the recording / busy states. */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Blue" to 0xFF2563EB.toInt(),
        "Teal" to 0xFF0D9488.toInt(),
        "Purple" to 0xFF7C3AED.toInt(),
        "Orange" to 0xFFEA580C.toInt(),
        "Pink" to 0xFFDB2777.toInt(),
        "Green" to 0xFF16A34A.toInt(),
        "Amber" to 0xFFD97706.toInt(),
        "Slate" to 0xFF475569.toInt(),
    )

    fun defaults(): List<VoiceButton> = listOf(
        VoiceButton("tap", PALETTE[0].second, pushToTalk = false),
        VoiceButton("ptt", PALETTE[1].second, pushToTalk = true),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)

    fun load(context: Context): List<VoiceButton> {
        val raw = prefs(context).getString(PREF, null) ?: return defaults()
        return parse(raw) ?: defaults()
    }

    fun save(context: Context, buttons: List<VoiceButton>) {
        prefs(context).edit().putString(PREF, serialize(buttons)).apply()
    }

    fun serialize(buttons: List<VoiceButton>): String = JSONArray().apply {
        buttons.forEach { b ->
            put(JSONObject().apply {
                put("id", b.id); put("color", b.color); put("ptt", b.pushToTalk); put("model", b.model)
            })
        }
    }.toString()

    fun parse(raw: String): List<VoiceButton>? = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VoiceButton(o.getString("id"), o.getInt("color"), o.optBoolean("ptt"), o.optString("model"))
        }.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    fun colorName(color: Int): String = PALETTE.firstOrNull { it.second == color }?.first ?: "Custom"

    /** New unused id for an added button. */
    fun newId(existing: List<VoiceButton>): String {
        var i = existing.size + 1
        while (existing.any { it.id == "b$i" }) i++
        return "b$i"
    }

    /**
     * The local model a button will use, or null for cloud transcription (tap buttons on
     * "automatic" while the cloud switch is on) or when no local model is installed.
     */
    fun resolveModel(context: Context, button: VoiceButton): String? {
        if (button.model.isNotBlank() && isLocalModelInstalled(context, button.model)) return button.model
        return if (button.pushToTalk) LocalTranscriber.pttModel(context)
        else if (!prefs(context).getBoolean("use_local", true)) null
        else LocalTranscriber.tapModel(context)
    }

    /**
     * Whether pressing the button can transcribe: it has a local model, or it is a tap
     * button on the cloud with a transcription key set.
     */
    fun isReady(context: Context, button: VoiceButton): Boolean {
        if (resolveModel(context, button) != null) return true
        if (button.pushToTalk) return false // push-to-talk is local only
        val cloud = !prefs(context).getBoolean("use_local", true)
        return cloud && OpenAiCompatibleApi.apiKey(prefs(context), OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF).isNotBlank()
    }
}

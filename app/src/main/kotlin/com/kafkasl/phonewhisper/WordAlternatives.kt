package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray

/**
 * Asks the cleanup (chat) model for likely replacements of one misrecognized word.
 * Speech recognizers return a single hypothesis, so alternatives come from an LLM that
 * sees the whole sentence.
 */
object WordAlternatives {

    const val MAX_SUGGESTIONS = 4

    const val SYSTEM_PROMPT = """You help fix speech-recognition mistakes.
The user gives a transcribed sentence and one word (or short fragment) from it that was recognized incorrectly.
Suggest up to 4 different replacements the speaker most likely actually said: similar in sound, fitting the context and grammar, in the same language as the sentence.
Never repeat the original word. Order from most to least likely.
Reply with only a JSON array of strings, for example ["word one", "word two"]."""

    fun userMessage(sentence: String, word: String): String =
        "Sentence: $sentence\nWrong word: $word"

    /** Parse the model reply: a JSON array, possibly fenced, or one suggestion per line. */
    fun parse(content: String, original: String): List<String> {
        val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val open = cleaned.indexOf('[')
        val close = cleaned.lastIndexOf(']')
        val raw: List<String> = if (open >= 0 && close > open) {
            try {
                val arr = JSONArray(cleaned.substring(open, close + 1))
                (0 until arr.length()).map { arr.optString(it) }
            } catch (_: Exception) {
                lines(cleaned)
            }
        } else {
            lines(cleaned)
        }
        return raw.map { it.trim().trim('"', '\'', '«', '»', '“', '”').trim() }
            .filter { it.isNotEmpty() && !it.equals(original, ignoreCase = true) && it.length <= 60 }
            .distinctBy { it.lowercase() }
            .take(MAX_SUGGESTIONS)
    }

    private fun lines(text: String): List<String> =
        text.lines().map { it.trim().replace(Regex("^([-*•]|\\d+[.)])\\s*"), "") }

    /** Result callback runs on an OkHttp background thread. */
    fun request(
        context: Context,
        sentence: String,
        word: String,
        callback: (suggestions: List<String>?, error: String?) -> Unit
    ) {
        val prefs = context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val apiKey = OpenAiCompatibleApi.apiKey(prefs, OpenAiCompatibleApi.CLEANUP_API_KEY_PREF)
        if (apiKey.isBlank()) {
            callback(null, null)
            return
        }
        val baseUrl = OpenAiCompatibleApi.apiBaseUrl(prefs, OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF)
        val model = OpenAiCompatibleApi.model(
            prefs, OpenAiCompatibleApi.CLEANUP_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL
        )
        PostProcessor.process(userMessage(sentence, word), SYSTEM_PROMPT, apiKey, baseUrl, model) { result ->
            if (result.text.isNullOrBlank()) callback(null, result.error ?: "empty response")
            else callback(parse(result.text, word), null)
        }
    }
}

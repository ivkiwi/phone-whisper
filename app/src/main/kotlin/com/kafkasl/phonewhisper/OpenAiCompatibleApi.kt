package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

object OpenAiCompatibleApi {
    const val LEGACY_API_KEY_PREF = "api_key"
    const val LEGACY_API_BASE_URL_PREF = "api_base_url"
    const val TRANSCRIPTION_API_KEY_PREF = "transcription_api_key"
    const val TRANSCRIPTION_API_BASE_URL_PREF = "transcription_api_base_url"
    const val TRANSCRIPTION_MODEL_PREF = "transcription_model"
    const val CLEANUP_API_KEY_PREF = "cleanup_api_key"
    const val CLEANUP_API_BASE_URL_PREF = "cleanup_api_base_url"
    const val CLEANUP_MODEL_PREF = "cleanup_model"
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
    const val DEFAULT_CLEANUP_MODEL = "gpt-4o-mini"

    data class ModelListResult(val models: List<String>?, val error: String?)

    private val client = OkHttpClient()

    fun normalizedBaseUrl(value: String?): String {
        val raw = value?.trim().orEmpty().ifBlank { DEFAULT_BASE_URL }
        return raw.trimEnd('/')
    }

    fun endpointUrl(baseUrl: String?, path: String): HttpUrl? {
        val base = "${normalizedBaseUrl(baseUrl)}/".toHttpUrlOrNull() ?: return null
        if (base.scheme != "https") return null
        return base.newBuilder()
            .addPathSegments(path.trim('/'))
            .build()
    }

    fun parseModelsResponse(json: String): ModelListResult {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                return ModelListResult(null, obj.getJSONObject("error").getString("message"))
            }

            val data = obj.optJSONArray("data") ?: return ModelListResult(null, "Unknown models response")
            val models = (0 until data.length())
                .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
                .distinct()
                .sorted()

            if (models.isEmpty()) ModelListResult(null, "No models in response")
            else ModelListResult(models, null)
        } catch (e: Exception) {
            ModelListResult(null, e.message ?: "Parse error")
        }
    }

    fun listModels(apiKey: String, apiBaseUrl: String, callback: (ModelListResult) -> Unit) {
        val endpoint = endpointUrl(apiBaseUrl, "models")
        if (endpoint == null) {
            callback(ModelListResult(null, "Invalid API base URL"))
            return
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(ModelListResult(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful && body.isBlank()) {
                    callback(ModelListResult(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseModelsResponse(body))
            }
        })
    }
}

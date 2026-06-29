package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient()

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        apiBaseUrl: String = OpenAiCompatibleApi.DEFAULT_BASE_URL,
        model: String = OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL,
        callback: (Result) -> Unit
    ) {
        val endpoint = OpenAiCompatibleApi.endpointUrl(apiBaseUrl, "audio/transcriptions")
        if (endpoint == null) {
            callback(Result(null, "Invalid transcription API base URL"))
            return
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model.ifBlank { OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL })
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) =
                callback(parseResponse(response.body?.string() ?: ""))
        })
    }
}

package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiCompatibleApiTest {

    @Test fun `uses default OpenAI base URL`() {
        val url = OpenAiCompatibleApi.endpointUrl("", "audio/transcriptions")

        assertEquals("https://api.openai.com/v1/audio/transcriptions", url.toString())
    }

    @Test fun `preserves custom base path`() {
        val url = OpenAiCompatibleApi.endpointUrl("https://example.com/proxy/v1/", "/chat/completions")

        assertEquals("https://example.com/proxy/v1/chat/completions", url.toString())
    }

    @Test fun `rejects invalid base URL`() {
        assertNull(OpenAiCompatibleApi.endpointUrl("not a url", "chat/completions"))
    }

    @Test fun `rejects cleartext base URL`() {
        assertNull(OpenAiCompatibleApi.endpointUrl("http://example.com/v1", "models"))
    }

    @Test fun `parses model list`() {
        val result = OpenAiCompatibleApi.parseModelsResponse(
            """{"data":[{"id":"gpt-4o-mini"},{"id":"whisper-1"},{"id":"gpt-4o-mini"}]}"""
        )

        assertEquals(listOf("gpt-4o-mini", "whisper-1"), result.models)
        assertNull(result.error)
    }
}

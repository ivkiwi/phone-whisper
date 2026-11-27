package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AppUpdaterTest {

    private fun release(tag: String, vararg assets: Pair<String, Long>) = JSONObject()
        .put("tag_name", tag)
        .put("assets", JSONArray(assets.map { (name, size) ->
            JSONObject().put("name", name).put("size", size)
                .put("browser_download_url", "https://example.test/$name")
        }))
        .toString()

    @Test fun `newer build with apk is an update`() {
        val update = AppUpdater.parseLatest(release("build-12", "notes.txt" to 1L, "phone-whisper.apk" to 42L), 7)
        assertEquals(AppUpdate(12, "https://example.test/phone-whisper.apk", 42), update)
    }

    @Test fun `same or older build is not an update`() {
        assertNull(AppUpdater.parseLatest(release("build-7", "phone-whisper.apk" to 42L), 7))
        assertNull(AppUpdater.parseLatest(release("build-3", "phone-whisper.apk" to 42L), 7))
    }

    @Test fun `non-build releases and releases without apk are ignored`() {
        assertNull(AppUpdater.parseLatest(release("models-gigaam-v4", "x.apk" to 1L), 7))
        assertNull(AppUpdater.parseLatest(release("build-x", "x.apk" to 1L), 7))
        assertNull(AppUpdater.parseLatest(release("build-12", "encoder.onnx" to 1L), 7))
    }
}

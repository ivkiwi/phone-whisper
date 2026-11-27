package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {

    private val releases = "https://github.com/ivkiwi/phone-whisper/releases"

    @Test fun `newer build is an update with its apk url`() {
        assertEquals(
            AppUpdate(12, "$releases/download/build-12/phone-whisper.apk"),
            AppUpdater.parseLatest("$releases/tag/build-12", 7),
        )
    }

    @Test fun `same or older build is not an update`() {
        assertNull(AppUpdater.parseLatest("$releases/tag/build-7", 7))
        assertNull(AppUpdater.parseLatest("$releases/tag/build-3", 7))
    }

    @Test fun `no release yet or non-build releases are ignored`() {
        assertNull(AppUpdater.parseLatest(releases, 7))
        assertNull(AppUpdater.parseLatest(null, 7))
        assertNull(AppUpdater.parseLatest("$releases/tag/models-gigaam-v4", 7))
        assertNull(AppUpdater.parseLatest("$releases/tag/build-x", 7))
    }
}

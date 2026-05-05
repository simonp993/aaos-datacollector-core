package com.porsche.aaos.platform.telemetry.core.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogcatLoggerTest {
    private val logger = LogcatLogger()

    @Test
    fun `format wraps tag in SportApps brackets`() {
        assertEquals("[SportApps:MMTR]", logger.format("MMTR"))
    }

    @Test
    fun `format handles empty tag`() {
        assertEquals("[SportApps:]", logger.format(""))
    }

    @Test
    fun `format preserves tag with special characters`() {
        assertEquals("[SportApps:ASI.Command]", logger.format("ASI.Command"))
    }
}

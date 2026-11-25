package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopExitLogTest {
    @Test
    fun `a kill is written with its reason and the system's own words`() {
        val text = DesktopExitLog.format(
            listOf(DesktopExitLog.Entry(1_700_000_000_000L, "com.vscodroid.debug", 3, 9, "too many child processes", 512L * 1024)),
        )
        assertTrue(text.contains("com.vscodroid.debug"))
        assertTrue(text.contains("killed: low memory"))
        assertTrue(text.contains("too many child processes"))
        assertTrue(text.contains("memory 512 MB"))
    }

    @Test
    fun `an unknown reason still prints its number`() {
        assertTrue(DesktopExitLog.reasonName(999).contains("999"))
    }
}

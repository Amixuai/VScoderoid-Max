package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the Desktop Mode screen asks of Termux:X11. */
class DesktopViewerTest {

    @Test
    fun `the picture is the whole display with nothing of Android's added`() {
        val p = DesktopViewer.SCREEN_PREFERENCES
        assertEquals(true, p["fullscreen"])
        assertEquals(true, p["showAdditionalKbd"], "the extra-keys bar is there for coding on a phone")
        assertEquals(true, p["additionalKbdVisible"])
        assertEquals("native", p["displayResolutionMode"])
        assertEquals(true, p["displayStretch"], "no black bands around the picture")
        assertEquals("3", p["touchMode"], "direct touch, not the trackpad")
        assertEquals("close top window", p["backButtonAction"], "Back never opens the keyboard")
        assertEquals("toggle soft keyboard", p["volumeDownAction"], "a fallback that needs no automation")
    }

    @Test
    fun `only booleans and strings are stored, because the activity writes only those`() {
        for ((key, value) in DesktopViewer.SCREEN_PREFERENCES) {
            assertTrue(value is Boolean || value is String, "$key is ${value::class}")
        }
    }

    @Test
    fun `the screen is the library's own activity, not a network address`() {
        assertEquals("com.termux.x11.MainActivity", DesktopViewer.X11_ACTIVITY)
        assertFalse(DesktopViewer.X11_ACTIVITY.contains("://"))
    }
}

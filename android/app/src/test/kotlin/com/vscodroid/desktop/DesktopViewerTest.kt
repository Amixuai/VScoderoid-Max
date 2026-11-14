package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the Desktop Mode screen asks for: the virtual screen size and the viewer address. */
class DesktopViewerTest {

    @Test
    fun `the screen is 1280 wide with the phone's own proportions, whichever way the phone is held`() {
        assertEquals("1280x600", DesktopViewer.geometry(2400, 1080), "20:9 is flatter than the floor allows")
        assertEquals("1280x720", DesktopViewer.geometry(1920, 1080))
        assertEquals("1280x800", DesktopViewer.geometry(2560, 1600))
        assertEquals("1280x800", DesktopViewer.geometry(1600, 2560), "portrait numbers give the same landscape screen")
    }

    @Test
    fun `the height is clamped and always even`() {
        assertEquals("1280x820", DesktopViewer.geometry(1600, 1600))
        for (w in 1000..3000 step 37) {
            val h = DesktopViewer.geometry(w, 1080).substringAfter('x').toInt()
            assertEquals(0, h % 2, "height $h for width $w")
            assertTrue(h in 600..820)
        }
    }

    @Test
    fun `a nonsense size still gives a usable geometry`() {
        assertTrue(Regex("""\d{3,4}x\d{3,4}""").matches(DesktopViewer.geometry(0, 0)))
    }

    @Test
    fun `the address carries the session secret and the tuning, and only loopback`() {
        val url = DesktopViewer.url("aB3dEf9h")
        assertTrue(url.startsWith("http://127.0.0.1:6080/vnc.html?"), url)
        assertTrue(url.contains("password=aB3dEf9h"))
        assertTrue(url.contains("autoconnect=1"))
        assertTrue(url.contains("resize=scale"))
        assertTrue(url.contains("quality=5"))
    }

    @Test
    fun `only the viewer origin may be shown`() {
        assertTrue(DesktopViewer.isViewerOrigin("http", "127.0.0.1", 6080))
        assertFalse(DesktopViewer.isViewerOrigin("https", "127.0.0.1", 6080))
        assertFalse(DesktopViewer.isViewerOrigin("http", "127.0.0.1", 8080))
        assertFalse(DesktopViewer.isViewerOrigin("http", "example.com", 6080))
        assertFalse(DesktopViewer.isViewerOrigin("http", null, -1))
    }
}

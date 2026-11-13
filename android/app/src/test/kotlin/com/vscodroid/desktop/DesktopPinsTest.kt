package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class DesktopPinsTest {

    private fun json(
        url: String = "",
        sha: String = "",
        bytes: Long = 0,
        dns: String = "[\"1.1.1.1\"]",
        suite: String = "trixie",
    ) = """
        {"schema":1,"suite":"$suite",
         "rootfs":{"url":"$url","sha256":"$sha","bytes":$bytes,"unpacked_bytes_estimate":600000000},
         "dns":$dns,
         "mirror":"http://deb.debian.org/debian","security_mirror":"http://deb.debian.org/debian-security"}
    """.trimIndent()

    @Test
    fun `the committed pins file parses and is shaped as the checker expects`() {
        val text = File("src/main/assets/desktop/pins.json").readText()
        val pins = DesktopPins.parse(text)
        assertEquals("trixie", pins.suite)
        assertTrue(pins.dns.isNotEmpty())
    }

    @Test
    fun `an empty pin is not pinned`() {
        assertFalse(DesktopPins.parse(json()).pinned)
    }

    @Test
    fun `a complete https pin is pinned`() {
        val pins = DesktopPins.parse(json("https://example.org/r.tar.xz", "a".repeat(64), 100))
        assertTrue(pins.pinned)
    }

    @Test
    fun `an http url is never pinned`() {
        assertFalse(DesktopPins.parse(json("http://example.org/r.tar.xz", "a".repeat(64), 100)).pinned)
    }

    @Test
    fun `an upper case digest is not pinned`() {
        assertFalse(DesktopPins.parse(json("https://example.org/r.tar.xz", "A".repeat(64), 100)).pinned)
    }

    @Test
    fun `a newline in a resolver address is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopPins.parse(json(dns = "[\"1.1.1.1\\nnameserver 6.6.6.6\"]"))
        }
    }

    @Test
    fun `a suite that is not a plain name is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopPins.parse(json(suite = "trixie\\nTypes: deb"))
        }
    }
}

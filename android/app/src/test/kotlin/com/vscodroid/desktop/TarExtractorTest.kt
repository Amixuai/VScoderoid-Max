package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Builds small tar archives by hand and unpacks them with [TarExtractor].
 *
 * The permission callback is a no-op here: android.system.Os does not exist on a
 * JVM, which is why the extractor takes it as a parameter.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class TarExtractorTest {

    private class Tar {
        val out = ByteArrayOutputStream()

        fun entry(name: String, type: Char, content: ByteArray = ByteArray(0), link: String = "", mode: Int = 420): Tar {
            val h = ByteArray(512)
            fun put(offset: Int, text: String) = text.toByteArray().copyInto(h, offset)
            put(0, name)
            put(100, "%07o".format(mode))
            put(108, "0000000")
            put(116, "0000000")
            put(124, "%011o".format(content.size))
            put(136, "00000000000")
            for (i in 148..155) h[i] = 0x20
            h[156] = type.code.toByte()
            put(157, link)
            put(257, "ustar")
            h[262] = 0
            put(263, "00")
            val sum = h.sumOf { it.toInt() and 0xFF }
            put(148, "%06o".format(sum))
            h[154] = 0
            h[155] = 0x20
            out.write(h)
            out.write(content)
            val pad = (512 - content.size % 512) % 512
            out.write(ByteArray(pad))
            return this
        }

        fun bytes(): ByteArray {
            val end = ByteArrayOutputStream()
            end.write(out.toByteArray())
            end.write(ByteArray(1024))
            return end.toByteArray()
        }
    }

    private fun extract(dest: File, tar: Tar): TarExtractor.Summary =
        TarExtractor(dest, chmod = { _, _ -> }).extract(ByteArrayInputStream(tar.bytes()))

    @Test
    fun `files directories and symlinks are created`(@TempDir dest: File) {
        val tar = Tar()
            .entry("./", '5')
            .entry("./usr/", '5')
            .entry("./usr/bin/", '5')
            .entry("./usr/bin/hello", '0', "hi".toByteArray())
            .entry("./bin", '2', link = "usr/bin")
        val summary = extract(dest, tar)
        assertEquals("hi", File(dest, "usr/bin/hello").readText())
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals(1, summary.files)
        assertEquals(1, summary.symlinks)
    }

    @Test
    fun `a hard link becomes a relative symlink to the first copy`(@TempDir dest: File) {
        val tar = Tar()
            .entry("./usr/", '5')
            .entry("./usr/bin/", '5')
            .entry("./usr/bin/a", '0', "x".toByteArray())
            .entry("./usr/bin/b", '1', link = "./usr/bin/a")
        val summary = extract(dest, tar)
        val link = File(dest, "usr/bin/b").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("a", Files.readSymbolicLink(link).toString())
        assertEquals(1, summary.hardLinksAsSymlinks)
    }

    @Test
    fun `devices are skipped and reported`(@TempDir dest: File) {
        val summary = extract(dest, Tar().entry("./dev/null", '3'))
        assertEquals(1, summary.skipped.size)
        assertFalse(File(dest, "dev/null").exists())
    }

    @Test
    fun `a dot dot component is refused`(@TempDir dest: File) {
        assertThrows(IOException::class.java) {
            extract(dest, Tar().entry("../evil", '0', "x".toByteArray()))
        }
    }

    @Test
    fun `nothing is written through a symlink an earlier entry made`(@TempDir dest: File) {
        val outside = File(dest, "outside").apply { mkdir() }
        val inner = File(dest, "tree").apply { mkdir() }
        val tar = Tar()
            .entry("./escape", '2', link = outside.absolutePath)
            .entry("./escape/pwned", '0', "x".toByteArray())
        assertThrows(IOException::class.java) { extract(inner, tar) }
        assertFalse(File(outside, "pwned").exists())
    }

    @Test
    fun `a damaged header is refused`(@TempDir dest: File) {
        val bytes = Tar().entry("./f", '0', "x".toByteArray()).bytes()
        bytes[10] = (bytes[10] + 1).toByte()
        assertThrows(IOException::class.java) {
            TarExtractor(dest, chmod = { _, _ -> }).extract(ByteArrayInputStream(bytes))
        }
    }
}

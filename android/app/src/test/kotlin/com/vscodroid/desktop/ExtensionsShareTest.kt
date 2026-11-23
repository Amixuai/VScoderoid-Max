package com.vscodroid.desktop

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The one extensions folder in the workspace. */
class ExtensionsShareTest {

    @Test
    fun `only the person's own extensions are brought over`() {
        assertTrue(ExtensionsShare.isImportable("ms-python.python-2024.1.0"))
        assertFalse(ExtensionsShare.isImportable("vscodroid.vscodroid-saf-bridge-1.12.1"))
        assertFalse(ExtensionsShare.isImportable(".obsolete"))
        assertFalse(ExtensionsShare.isImportable("extensions.json"))
        assertFalse(ExtensionsShare.isImportable(""))
    }

    @Test
    fun `extensions are copied once and never over what is already there`(@TempDir dir: File) {
        val source = File(dir, "old").apply { mkdirs() }
        File(source, "a.b-1.0/package.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(source, "vscodroid.welcome-1/package.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(source, "extensions.json").writeText("[]")
        val target = File(dir, "shared")

        assertEquals(1, ExtensionsShare.importOnce(source, target))
        assertTrue(File(target, "a.b-1.0/package.json").isFile)
        assertFalse(File(target, "vscodroid.welcome-1").exists())
        assertTrue(File(target, "extensions.json").isFile)

        File(source, "c.d-2.0/package.json").apply { parentFile.mkdirs(); writeText("{}") }
        assertEquals(0, ExtensionsShare.importOnce(source, target), "a folder that has extensions is left alone")
        assertFalse(File(target, "c.d-2.0").exists())
    }

    @Test
    fun `a missing source is nothing to do`(@TempDir dir: File) {
        assertEquals(0, ExtensionsShare.importOnce(File(dir, "none"), File(dir, "shared")))
    }
}

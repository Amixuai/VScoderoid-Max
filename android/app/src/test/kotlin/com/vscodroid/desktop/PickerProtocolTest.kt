package com.vscodroid.desktop

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The text exchanged with the Debian side, and the folder it travels through. */
class PickerProtocolTest {
    private val root = File("/storage/emulated/0")

    @Test
    fun `a request is read back as written`() {
        val r = PickerProtocol.parse("1795318303989\ndir\n\n")
        assertEquals(PickerProtocol.Request("1795318303989", PickerProtocol.Kind.Folder, ""), r)
        assertEquals("notes.txt", PickerProtocol.parse("42\nsave\nnotes.txt\n")?.name)
        assertEquals(PickerProtocol.Kind.Files, PickerProtocol.parse("42\nfiles\n\n")?.kind)
    }

    @Test
    fun `anything that is not a request is refused`() {
        assertNull(PickerProtocol.parse(""))
        assertNull(PickerProtocol.parse("42"))
        assertNull(PickerProtocol.parse("4 2\ndir\n"))
        assertNull(PickerProtocol.parse("42\nformat\n"))
    }

    @Test
    fun `an answer is the id then one path per line, and a cancel is the id alone`() {
        assertEquals("7\n/a/b\n/a/c\n", PickerProtocol.response("7", listOf("/a/b", "/a/c")))
        assertEquals("7\n", PickerProtocol.response("7", emptyList()))
    }

    @Test
    fun `documents of the phone storage become paths and others do not`() {
        val ext = "com.android.externalstorage.documents"
        assertEquals("/storage/emulated/0/.VScoderoid-Pro/app", PickerProtocol.pathForDocument(ext, "primary:.VScoderoid-Pro/app", root))
        assertEquals("/storage/emulated/0", PickerProtocol.pathForDocument(ext, "primary:", root))
        assertEquals("/storage/ABCD-1234/work", PickerProtocol.pathForDocument(ext, "ABCD-1234:work", root))
        assertEquals("/storage/emulated/0/Download/x.txt",
            PickerProtocol.pathForDocument("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/x.txt", root))
        assertNull(PickerProtocol.pathForDocument("com.android.providers.downloads.documents", "msf:123", root))
        assertNull(PickerProtocol.pathForDocument(ext, "primary:../etc", root))
        assertNull(PickerProtocol.pathForDocument("com.google.android.apps.docs.storage", "abc", root))
    }

    @Test
    fun `the folder the Android screen reads is the one the Debian tree lives in`() {
        // MainActivity.java of Termux:X11 (patched by scripts/patch-termux-x11.py) spells this name out.
        assertEquals("debian_rootfs", DesktopRuntime.ROOTFS_NAME)
    }
}

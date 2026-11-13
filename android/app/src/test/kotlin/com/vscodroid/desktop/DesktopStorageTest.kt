package com.vscodroid.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun storage(): Triple<DesktopStorage, File, File> {
        val engine = tmp.newFolder("files")
        val pub = tmp.newFolder("public")
        val rootfs = tmp.newFolder("rootfs")
        return Triple(DesktopStorage(engine, pub, rootfs) { _, _ -> }, pub, engine)
    }

    @Test
    fun nodeProjectGetsABindAndAnEmptyMountPoint() {
        val (s, pub, engine) = storage()
        File(pub, "app").mkdirs()
        File(pub, "app/package.json").writeText("{}")
        val plan = s.plan()
        val bind = plan.binds.single { it.guest == "/root/workspace/app/node_modules" }
        assertTrue(File(pub, "app/node_modules").isDirectory)
        assertEquals(0, File(pub, "app/node_modules").list()!!.size)
        assertTrue(bind.host.absolutePath.startsWith(File(engine, "local_project_deps").absolutePath))
        assertTrue(bind.host.isDirectory)
    }

    @Test
    fun aFolderThatAlreadyHoldsFilesIsNotHidden() {
        val (s, pub, _) = storage()
        File(pub, "app/node_modules").mkdirs()
        File(pub, "app/package.json").writeText("{}")
        File(pub, "app/node_modules/keep.txt").writeText("x")
        val plan = s.plan()
        assertFalse(plan.binds.any { it.guest.endsWith("node_modules") })
        assertEquals(1, plan.skipped.size)
    }

    @Test
    fun unsafeAndHiddenNamesAreNeverBound() {
        val (s, pub, _) = storage()
        File(pub, "a:b").mkdirs(); File(pub, "a:b/package.json").writeText("{}")
        File(pub, ".hidden").mkdirs(); File(pub, ".hidden/package.json").writeText("{}")
        val plan = s.plan()
        assertFalse(plan.binds.any { it.guest.contains("a:b") || it.guest.contains(".hidden") })
        assertEquals(listOf("a:b"), plan.skipped.map { it.project })
    }

    @Test
    fun severalLanguagesInOneProject() {
        val (s, pub, _) = storage()
        File(pub, "mix").mkdirs()
        File(pub, "mix/Cargo.toml").writeText("")
        File(pub, "mix/requirements.txt").writeText("")
        val guests = s.plan().binds.map { it.guest }
        assertTrue("/root/workspace/mix/target" in guests)
        assertTrue("/root/workspace/mix/.venv" in guests)
    }

    @Test
    fun deletingAProjectRemovesItsPrivateData() {
        val (s, pub, engine) = storage()
        File(pub, "app").mkdirs(); File(pub, "app/package.json").writeText("{}")
        val host = s.plan().binds.single { it.guest.endsWith("node_modules") }.host
        File(host, "left.txt").writeText("x")
        File(pub, "app").deleteRecursively()
        val removed = s.cleanOrphans()
        assertEquals(1, removed.size)
        assertFalse(host.exists())
        assertTrue(File(engine, "local_project_deps").isDirectory)
    }

    @Test
    fun cleanupLeavesFoldersItDidNotMake() {
        val (s, _, engine) = storage()
        val foreign = File(engine, "local_project_deps/other").apply { mkdirs() }
        File(foreign, "f").writeText("x")
        assertTrue(s.cleanOrphans().isEmpty())
        assertTrue(foreign.exists())
    }

    @Test
    fun projectIdIsStableAndPathSafe() {
        assertEquals(DesktopStorage.projectId("my app"), DesktopStorage.projectId("my app"))
        assertTrue(DesktopStorage.projectId("my app").matches(Regex("[A-Za-z0-9_-]+-[0-9a-f]{8}")))
        assertTrue(DesktopStorage.projectId("a") != DesktopStorage.projectId("b"))
    }

    @Test
    fun privateTreeAndCacheBinds() {
        val (s, _, engine) = storage()
        s.ensurePrivateTree()
        for (d in listOf("proot_layer", "global_libraries/node_modules", "global_libraries/pip_packages", "local_project_deps", "logs")) assertTrue(d, File(engine, d).isDirectory)
        assertEquals(6, s.globalBinds().size)
    }
}

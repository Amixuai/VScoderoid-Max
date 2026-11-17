package com.vscodroid.desktop

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The storage layout of Desktop Mode (work package WP3a, brief section 13).
 *
 * Two zones. The PRIVATE zone is `<filesDir>`: `proot_layer/` (the start script),
 * `debian_rootfs/` (the Debian tree, WP2), `global_libraries/` and
 * `local_project_deps/`, the names of the Master Blueprint, section 5. It is the
 * only place that can hold programs, symlinks and hard links. The PUBLIC zone is
 * the user's workspace on shared storage, which holds only what a person writes.
 *
 * Shared storage cannot hold symlinks or an executable bit, which the heavy
 * per-project folders (`node_modules`, `.venv`, `target`, `vendor`, `build`) need.
 * So each of those is kept in `engine/deps/<project-id>/` and bound by PRoot onto
 * the same path inside the project. The project still shows an empty folder in the
 * file manager and the full one inside Linux; nothing is a symlink.
 *
 * This class decides and prepares; it starts nothing. [binds] is what the launcher
 * hands to PRoot as `-b host:guest`.
 *
 * The global folders are outside the Debian tree on purpose: a replaced tree (the
 * seamless engine migration of section 6) keeps every global install and every
 * project's dependencies.
 *
 * STATUS: UNTESTED on a device. The pure logic is covered by DesktopStorageTest.
 */
class DesktopStorage(
    /** The app's private files folder; the layout below is created directly inside it. */
    private val privateRoot: File,
    private val publicRoot: File,
    private val rootfsDir: File,
    private val chmod: (File, Int) -> Unit,
) {
    /** One `-b host:guest` pair. */
    data class Bind(val host: File, val guest: String) {
        fun spec(): String = "${host.absolutePath}:$guest"
    }

    /** A project folder that was left out, and why. [reason] is for logs, not for a screen. */
    data class Skipped(val project: String, val reason: String)

    data class Plan(val binds: List<Bind>, val skipped: List<Skipped>)

    /** local_project_deps/ (section 5, item 6): the physical home of every project's heavy folders. */
    private val depsDir = File(privateRoot, "local_project_deps")

    /** global_libraries/ (item 5): global package-manager installs, kept outside the Debian tree. */
    private val globalDir = File(privateRoot, "global_libraries")

    /** Creates the private folders of the layout and the guest mount points the global binds need. */
    fun ensurePrivateTree() {
        for (dir in listOf("proot_layer", "local_project_deps", "global_libraries", "logs")) File(privateRoot, dir).mkdirs()
        for ((name, _) in GLOBALS) File(globalDir, name).mkdirs()
        val verified = HashSet<String>()
        for ((_, guest) in GLOBALS) {
            GuestFs.ensureDirs(rootfsDir, GuestFs.parts(guest), chmod, verified)
        }
        GuestFs.ensureDirs(rootfsDir, GuestFs.parts(GUEST_WORKSPACE), chmod, verified)
    }

    /**
     * Creates the public workspace folder. Its `.nomedia` is written by the
     * existing PublicWorkspace guard, which also restores a renamed folder, so
     * this only makes sure the directory is there for a test or a first run.
     */
    fun ensurePublicTree() {
        if (!publicRoot.isDirectory && !publicRoot.mkdirs()) {
            throw IOException("could not create the workspace folder")
        }
        val nomedia = File(publicRoot, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
    }

    /** The global installs, each in private storage and bound to the place the tool uses in the guest. */
    fun globalBinds(): List<Bind> = GLOBALS.map { (name, guest) -> Bind(File(globalDir, name), guest) }

    /**
     * The workspace bind plus one bind per heavy folder of each project (13.4),
     * after creating the empty mount-point folder in the project. A folder that
     * already holds files in shared storage is left alone: binding over it would
     * hide the user's data.
     */
    fun plan(): Plan {
        val binds = ArrayList<Bind>()
        val skipped = ArrayList<Skipped>()
        binds += Bind(publicRoot, GUEST_WORKSPACE)
        binds += globalBinds()
        val projects = publicRoot.listFiles { f -> f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath()) }
            ?.sortedBy { it.name }.orEmpty()
        for (project in projects) {
            val name = project.name
            if (!isSafeName(name)) {
                if (!name.startsWith(".")) skipped += Skipped(name, "the folder name has characters PRoot cannot bind")
                continue
            }
            val folders = heavyFolders(project)
            if (folders.isEmpty()) continue
            val id = projectId(name)
            for (folder in folders) {
                val mountPoint = File(project, folder)
                if (mountPoint.exists() && !isEmptyDirectory(mountPoint)) {
                    skipped += Skipped(name, "$folder already holds files in shared storage")
                    continue
                }
                if (!mountPoint.exists() && !mountPoint.mkdirs()) {
                    skipped += Skipped(name, "could not create $folder")
                    continue
                }
                val host = File(File(depsDir, id), folder)
                host.mkdirs()
                File(File(depsDir, id), PROJECT_FILE).writeText(name + "\n")
                binds += Bind(host, "$GUEST_WORKSPACE/$name/$folder")
            }
        }
        return Plan(binds, skipped)
    }

    /**
     * Removes `deps/<id>` for projects that no longer exist in the workspace, so
     * deleting a project in the file manager leaves no orphan data. Only direct
     * children of `deps` that carry this app's own marker are touched, and each is
     * removed through [GuestFs.deleteTree]. Returns the ids removed.
     */
    fun cleanOrphans(): List<String> {
        val removed = ArrayList<String>()
        val children = depsDir.listFiles { f -> f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath()) }.orEmpty()
        for (child in children) {
            val marker = File(child, PROJECT_FILE)
            if (!marker.isFile) continue
            val name = runCatching { marker.readText().trim() }.getOrNull() ?: continue
            if (!isSafeName(name) || projectId(name) != child.name) continue
            if (File(publicRoot, name).isDirectory) continue
            GuestFs.deleteTree(child, depsDir)
            removed += child.name
        }
        return removed
    }

    companion object {
        const val GUEST_WORKSPACE = "/root/workspace"
        private const val PROJECT_FILE = ".project"

        /**
         * Folder of global_libraries/ to the place the tool looks in the guest. node_modules, pip_packages
         * and composer_vendor are the three of the brief; the others keep the Rust, Go and npm download
         * caches out of the tree too, so replacing the tree does not re-download them. The tools are
         * pointed at these places by [RootfsProvisioner] (an .npmrc prefix, pip.conf, PATH).
         */
        val GLOBALS: List<Pair<String, String>> = listOf(
            "node_modules" to "/root/.npm-global",
            "pip_packages" to "/root/.local",
            "composer_vendor" to "/root/.config/composer",
            "cargo_home" to "/root/.cargo",
            "go_path" to "/root/go",
            "npm_cache" to "/root/.npm",
        )

        /** Project marker files and the heavy folders they imply (13.4). */
        private val RULES: List<Pair<List<String>, List<String>>> = listOf(
            listOf("package.json") to listOf("node_modules"),
            listOf("requirements.txt", "pyproject.toml", "setup.py", "Pipfile") to listOf(".venv"),
            listOf("Cargo.toml") to listOf("target"),
            listOf("composer.json") to listOf("vendor"),
            listOf("CMakeLists.txt") to listOf("build"),
            listOf("pom.xml") to listOf("target"),
            listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") to listOf("build", ".gradle"),
        )

        /** Names PRoot can take in `-b host:guest`: no colon, comma or control character, nothing hidden. */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 100 && !name.startsWith(".") &&
                name.all { it.isLetterOrDigit() || it in "._- " }

        /** Stable, filesystem-safe id for a project: its name plus a short digest of it. */
        fun projectId(name: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8))
            val short = digest.take(4).joinToString("") { "%02x".format(it) }
            val readable = name.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(40)
            return "$readable-$short"
        }

        internal fun heavyFolders(project: File): List<String> {
            val out = LinkedHashSet<String>()
            for ((markers, folders) in RULES) {
                if (markers.any { File(project, it).isFile }) out.addAll(folders)
            }
            return out.toList()
        }

        private fun isEmptyDirectory(dir: File): Boolean =
            dir.isDirectory && !java.nio.file.Files.isSymbolicLink(dir.toPath()) && dir.list().orEmpty().isEmpty()
    }
}

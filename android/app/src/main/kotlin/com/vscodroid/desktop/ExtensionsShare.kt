package com.vscodroid.desktop

import java.io.File

/**
 * The editor's extensions live in one folder of the workspace (`<workspace>/.extensions`), so they
 * are kept when the app's other parts go and are not downloaded twice. Desktop Mode's editor is
 * given that folder (wrapper.sh); this brings over, once, the extensions the app's other editor
 * has already installed.
 *
 * STATUS: unit-tested on a JVM only; never run against a device.
 */
object ExtensionsShare {
    const val FOLDER = ".extensions"

    /** The registry file the editor keeps beside the extension folders. */
    private const val REGISTRY = "extensions.json"

    /** The app's own bundled extensions are not the person's and are left where they are. */
    private const val BUNDLED_PREFIX = "vscodroid."

    fun sharedDir(workspace: File): File = File(workspace, FOLDER)

    /** True for an extension folder that was installed by the person. */
    fun isImportable(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && !name.startsWith(BUNDLED_PREFIX) && name != REGISTRY

    /**
     * Copies the person's extensions from [source] into [target] when [target] has nothing yet.
     * Returns how many extension folders were copied; a folder that cannot be copied is skipped.
     */
    fun importOnce(source: File, target: File): Int {
        if (!source.isDirectory || target.list().orEmpty().isNotEmpty()) return 0
        target.mkdirs()
        var copied = 0
        for (child in source.listFiles().orEmpty()) {
            if (!child.isDirectory || !isImportable(child.name)) continue
            val ok = child.copyRecursively(File(target, child.name), overwrite = false) { _, _ ->
                kotlin.io.OnErrorAction.SKIP
            }
            if (ok) copied++
        }
        val registry = File(source, REGISTRY)
        if (copied > 0 && registry.isFile) runCatching { registry.copyTo(File(target, REGISTRY), overwrite = false) }
        return copied
    }
}

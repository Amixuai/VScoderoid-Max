package com.vscodroid.desktop

import android.content.Context
import android.content.res.AssetManager
import android.os.StatFs
import android.system.Os
import org.json.JSONObject
import java.io.File

/**
 * Assembles the Desktop Mode pieces from the app's own context, so that the debug
 * driver now and the real screens of WP5 later build them the same way.
 *
 * THE SYSTEM COMES WITH THE APP. `assets/desktop/rootfs.tar.xz` is the Debian system
 * with the screen stack, the editor and the toolchain already installed, put there
 * by scripts/build-desktop-rootfs.sh during the build; `rootfs.meta.json` beside it
 * carries its digest and sizes. First use therefore only unpacks it. A build that
 * lacks the asset (a developer build made without running that script) falls back
 * to the pinned download in `pins.json`.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
object DesktopRuntime {
    /** The Debian tree's folder name in filesDir (Master Blueprint, section 5). */
    const val ROOTFS_NAME = "debian_rootfs"

    private const val ASSET_ROOTFS = "desktop/rootfs.tar.xz"
    private const val ASSET_META = "desktop/rootfs.meta.json"
    private const val ASSET_ALIASES = "desktop/10-compat-aliases.sh"

    /** The bundled system, or null when this build does not carry one. */
    fun bundled(context: Context): RootfsProvisioner.Bundled? {
        val meta = try {
            context.assets.open(ASSET_META).bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: java.io.IOException) {
            return null
        }
        return RootfsProvisioner.Bundled(
            open = { context.assets.open(ASSET_ROOTFS, AssetManager.ACCESS_STREAMING) },
            sha256 = meta.getString("sha256"),
            bytes = meta.getLong("bytes"),
            unpackedBytes = meta.getLong("unpacked_bytes"),
        )
    }

    fun provisioner(
        context: Context,
        pins: DesktopPins = DesktopPins.load(context),
        progress: (RootfsProvisioner.Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) = RootfsProvisioner(
        desktopDir = context.filesDir,
        pins = pins,
        freeBytes = { StatFs(context.filesDir.absolutePath).availableBytes },
        chmod = { file, mode -> Os.chmod(file.path, mode) },
        compatAliases = runCatching {
            context.assets.open(ASSET_ALIASES).bufferedReader().use { it.readText() }
        }.getOrDefault(""),
        bundled = bundled(context),
        rootfsName = ROOTFS_NAME,
        isCancelled = isCancelled,
        progress = progress,
    )

    fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_NAME)
}

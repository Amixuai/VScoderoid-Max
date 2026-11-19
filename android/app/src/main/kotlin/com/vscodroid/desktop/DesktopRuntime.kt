package com.vscodroid.desktop

import android.content.Context
import android.os.StatFs
import android.system.Os
import java.io.File

/**
 * Assembles the Desktop Mode pieces from the app's own context, so that the debug
 * driver now and the real screens of WP5 later build them the same way.
 *
 * THE SYSTEM IS DOWNLOADED, NOT SHIPPED. The APK carries only the app. The Debian system with
 * the screen stack, the editor and the toolchain is built by CI (scripts/build-desktop-rootfs.sh),
 * published as a release file with a manifest, and fetched by [RootfsProvisioner] the first time
 * the app runs, straight into the app's private folder.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
object DesktopRuntime {
    /** The Debian tree's folder name in filesDir (Master Blueprint, section 5). */
    const val ROOTFS_NAME = "debian_rootfs"

    private const val ASSET_ALIASES = "desktop/10-compat-aliases.sh"

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
        rootfsName = ROOTFS_NAME,
        isCancelled = isCancelled,
        progress = progress,
    )

    fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_NAME)
}

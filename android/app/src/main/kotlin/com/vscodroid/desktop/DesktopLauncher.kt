package com.vscodroid.desktop

import android.content.Context
import com.vscodroid.storage.PublicWorkspace
import com.vscodroid.util.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Starts, stops and checks the Desktop Mode display and session (work package WP3).
 *
 * The display is Termux:X11, built into this app: its X server is started here as a
 * process of its own (app_process running com.termux.x11.CmdEntryPoint, the way Termux
 * runs it), with its socket in the Debian tree's /tmp so the guest sees it as
 * /tmp/.X11-unix/X0. The Debian side is one proot, whose whole command line lives in
 * `assets/desktop/wrapper.sh`; this class sets the environment that script reads, runs
 * it with the app's own bash, and waits for the display to answer from inside the guest.
 * It holds the two long-lived processes (X server and proot), because the service of
 * WP5 has to own them.
 *
 * FALLBACK. If the first start does not bring the display up, it is tried again
 * once with PROOT_NO_SECCOMP=1. When that works the choice is written to
 * `proot_layer/seccomp.fallback` and used from then on, so a device that needs it
 * pays the failed attempt once.
 *
 * It writes no user-visible text. [StartResult] is what WP5's screen explains,
 * with strings in `res/values`. There is no network port and no password: the display
 * is a unix socket inside the app's private folder.
 *
 * BLOCKING: call from a worker thread.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class DesktopLauncher(
    private val bashPath: String,
    private val scriptFile: File,
    /** filesDir/proot_layer: the script and the session's own files. */
    private val layerDir: File,
    /** filesDir/debian_rootfs. */
    private val rootfs: File,
    private val logFile: File,
    private val baseEnvironment: Map<String, String>,
    private val prootPath: String,
    private val storage: DesktopStorage,
    /** The installed APK, put on CLASSPATH so app_process finds the X server classes. */
    private val apkPath: String,
    /** Where the extracted native libraries are, for libXlorie.so. */
    private val nativeLibDir: String,
    /** This app's package name (it differs between debug and release builds). */
    private val hostPackage: String,
    private val x11LogFile: File,
    /** The device's screen density (1.0 = 160 dpi), so the editor is as large as on the phone's own apps. */
    private val density: Float = 1f,
    private val readyTimeoutSeconds: Long = READY_TIMEOUT_SECONDS,
) {
    sealed class StartResult {
        data class Running(val usedSeccompFallback: Boolean) : StartResult()
        object NotProvisioned : StartResult()
        data class Failed(val detail: String) : StartResult()
    }

    data class Status(val xServerUp: Boolean, val sessionUp: Boolean) {
        val healthy: Boolean get() = xServerUp && sessionUp
    }

    private val fallbackMarker = File(layerDir, "seccomp.fallback")
    private var session: Process? = null
    private var xServer: Process? = null

    /** The process of the running session, for the service that owns it. */
    fun process(): Process? = session

    /** The X server process, for the same reason. */
    fun xServerProcess(): Process? = xServer

    fun start(): StartResult {
        if (!File(rootfs, "usr/bin/env").exists()) return StartResult.NotProvisioned
        stop()
        storage.ensurePrivateTree()
        storage.ensurePublicTree()
        storage.cleanOrphans()
        rotateLog()
        val plan = storage.plan()
        val remembered = fallbackMarker.isFile
        startXServer()?.let { return StartResult.Failed(it) }
        val first = launch(plan, noSeccomp = remembered)
        if (first == null) return StartResult.Running(remembered)
        if (remembered) { stop(); return StartResult.Failed(first) }
        stop()
        startXServer()?.let { return StartResult.Failed("$first | then: $it") }
        val second = launch(plan, noSeccomp = true)
        if (second == null) {
            runCatching { fallbackMarker.writeText("1\n") }
            return StartResult.Running(true)
        }
        stop()
        return StartResult.Failed("$first | with PROOT_NO_SECCOMP=1: $second")
    }

    /** Ends the session the app holds, then anything of this tree that is left. */
    fun stop(): Int {
        session?.let { p ->
            p.destroy()
            if (!p.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        session = null
        xServer?.let { p ->
            p.destroy()
            if (!p.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        xServer = null
        // desktop_stop also ends an X server left behind by an earlier run of the app.
        return runScript("desktop_stop", emptyMap(), 20)?.trim()?.toIntOrNull() ?: 0
    }

    fun status(): Status = Status(
        xServerUp = xServer?.isAlive == true && File(rootfs, "tmp/.X11-unix/X0").exists(),
        sessionUp = session?.isAlive == true && File(rootfs, "tmp/$READY_MARKER").exists(),
    )

    /** One-time package install inside the tree. Returns null on success, else the tail of the output. */
    fun installStack(): String? {
        val out = StringBuilder()
        val exit = runScriptExit("desktop_install_stack", emptyMap(), INSTALL_TIMEOUT_SECONDS, out)
        if (exit == 0) return null
        // apt can be left half-configured by a kill; one repair pass, then one more try.
        runScriptExit("desktop_proot_exec -- dpkg --configure -a", emptyMap(), REPAIR_TIMEOUT_SECONDS, StringBuilder())
        out.setLength(0)
        val again = runScriptExit("desktop_install_stack", emptyMap(), INSTALL_TIMEOUT_SECONDS, out)
        return if (again == 0) null else out.toString().takeLast(DETAIL_LIMIT)
    }

    /**
     * Runs one command inside the Debian tree, with the workspace and project binds when
     * [withBinds] is set. Returns the exit status (negative if it could not start or timed
     * out) and the output tail. For acceptance tests and the storage capability test.
     */
    fun runInGuest(args: List<String>, timeoutSeconds: Long, withBinds: Boolean): Pair<Int, String> {
        storage.ensurePrivateTree()
        val plan = if (withBinds) { storage.ensurePublicTree(); storage.plan() } else DesktopStorage.Plan(emptyList(), emptyList())
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val out = StringBuilder()
        val exit = runScriptExit("desktop_proot_exec -- $quoted", emptyMap(), timeoutSeconds, out, plan)
        return exit to out.toString().trim()
    }

    // ----------------------------------------------------------------- internals

    /**
     * Starts Termux:X11's X server as a process of this app and waits for its socket.
     * Null when it is up, otherwise why not. The socket lives in the tree's /tmp, so the
     * guest finds it as /tmp/.X11-unix/X0 with no bind and no network port.
     */
    private fun startXServer(): String? {
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        File(tmp, ".X11-unix").mkdirs()
        for (leftover in listOf(".X0-lock", ".X11-unix/X0", READY_MARKER)) File(tmp, leftover).delete()
        x11LogFile.parentFile?.mkdirs()
        val appProcess = listOf("/system/bin/app_process64", "/system/bin/app_process").firstOrNull { File(it).exists() }
            ?: return "app_process was not found"
        val builder = ProcessBuilder(appProcess, "/", "--nice-name=vscodroid-x11", "com.termux.x11.CmdEntryPoint", ":0")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(x11LogFile))
        // The system's own environment (ANDROID_ROOT, BOOTCLASSPATH and the rest), not the
        // app's tool environment: app_process must start like any Android process.
        for (k in DROP) builder.environment().remove(k)
        builder.environment().apply {
            put("CLASSPATH", apkPath)
            put("TMPDIR", tmp.absolutePath)
            put("XKB_CONFIG_ROOT", File(rootfs, "usr/share/X11/xkb").absolutePath)
            put("VSCODROID_X11_HOST", hostPackage)
            put("VSCODROID_X11_NATIVE_DIR", nativeLibDir)
        }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return "could not start the display server: ${e.message}"
        }
        xServer = process
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(X_READY_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return "the display server ended with exit ${process.exitValue()}: ${x11LogTail()}"
            if (File(tmp, ".X11-unix/X0").exists()) return null
            Thread.sleep(POLL_MS)
        }
        return "the display server did not open its socket in ${X_READY_TIMEOUT_SECONDS}s: ${x11LogTail()}"
    }

    /** Null when the display answered inside the guest, otherwise why not. */
    private fun launch(plan: DesktopStorage.Plan, noSeccomp: Boolean): String? {
        logFile.parentFile?.mkdirs()
        val env = environment(plan, noSeccomp)
        val builder = ProcessBuilder(bashPath, "-c", ". '${scriptFile.absolutePath}'; desktop_start")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        builder.environment().clear()
        builder.environment().putAll(env)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return "could not start: ${e.message}"
        }
        session = process
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(readyTimeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return "the session ended with exit ${process.exitValue()}: ${logTail()} | ${guestLogTail()}"
            if (xServer?.isAlive != true) return "the display server ended: ${x11LogTail()}"
            if (status().healthy) return null
            Thread.sleep(POLL_MS)
        }
        return "the display did not answer in ${readyTimeoutSeconds}s: ${logTail()} | ${guestLogTail()} | x11: ${x11LogTail()}"
    }

    private fun environment(plan: DesktopStorage.Plan, noSeccomp: Boolean): Map<String, String> {
        val env = HashMap(baseEnvironment)
        for (k in DROP) env.remove(k)
        env["PROOT"] = prootPath
        env["DESKTOP_ROOT"] = rootfs.absolutePath
        env["DESKTOP_DIR"] = layerDir.absolutePath
        env["DESKTOP_BINDS"] = plan.binds.joinToString("\n") { it.spec() }
        env["DESKTOP_NO_SECCOMP"] = if (noSeccomp) "1" else "0"
        env["DESKTOP_WORKSPACE"] = DesktopStorage.GUEST_WORKSPACE
        env["DESKTOP_DENSITY"] = java.lang.String.format(java.util.Locale.US, "%.2f", density.coerceIn(1f, 4f))
        return env
    }

    private fun runScript(call: String, extra: Map<String, String>, timeoutSeconds: Long): String? {
        val out = StringBuilder()
        return if (runScriptExit(call, extra, timeoutSeconds, out) == 0) out.toString() else out.toString().ifBlank { null }
    }

    private fun runScriptExit(
        call: String,
        extra: Map<String, String>,
        timeoutSeconds: Long,
        out: StringBuilder,
        plan: DesktopStorage.Plan = DesktopStorage.Plan(emptyList(), emptyList()),
    ): Int {
        val env = environment(plan, fallbackMarker.isFile)
        val builder = ProcessBuilder(bashPath, "-c", ". '${scriptFile.absolutePath}'; $call").redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(env)
        builder.environment().putAll(extra)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            out.append("could not start: ${e.message}")
            return -1
        }
        val reader = Thread {
            process.inputStream.bufferedReader().use { r ->
                val buffer = CharArray(1024)
                while (true) {
                    val n = r.read(buffer)
                    if (n < 0) break
                    out.append(buffer, 0, n)
                    if (out.length > 16384) out.delete(0, out.length - 8192)
                }
            }
        }
        reader.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2000)
            out.append(" [timed out after ${timeoutSeconds}s]")
            return -2
        }
        reader.join(2000)
        return process.exitValue()
    }

    /** Keeps the app's own session log from growing without end: over 1 MiB, only the last 256 KiB stay. */
    private fun rotateLog() {
        runCatching {
            if (logFile.length() <= LOG_LIMIT_BYTES) return
            val keep = ByteArray(LOG_KEEP_BYTES)
            val read = logFile.inputStream().use { input ->
                input.skip(logFile.length() - LOG_KEEP_BYTES)
                input.read(keep)
            }
            logFile.writeBytes(keep.copyOf(maxOf(read, 0)))
        }
    }

    private fun logTail(): String =
        runCatching { logFile.readText().takeLast(DETAIL_LIMIT).replace('\n', ' ') }.getOrDefault("(no log)")

    private fun x11LogTail(): String =
        runCatching { x11LogFile.readText().takeLast(DETAIL_LIMIT).replace('\n', ' ') }.getOrDefault("(no log)")

    /**
     * The end of the guest's own logs (window manager, editor), which live inside the
     * unpacked system and so cannot be read by the person using the phone. They go into
     * the failure text the screen shows, so a failed start can be reported from a
     * screenshot.
     */
    private fun guestLogTail(): String = listOf("openbox", "codium").joinToString(" | ") { name ->
        val tail = runCatching { File(rootfs, "tmp/$name.log").readText().takeLast(GUEST_LOG_TAIL).replace('\n', ' ') }
            .getOrDefault("(none)")
        "$name: $tail"
    }

    companion object {
        const val ASSET_SCRIPT = "desktop/wrapper.sh"
        /** Written inside the guest's /tmp once `xdpyinfo` has reached the display. */
        private const val READY_MARKER = ".vsc-display-ready"
        private const val X_READY_TIMEOUT_SECONDS = 30L
        private const val READY_TIMEOUT_SECONDS = 90L
        private const val STOP_GRACE_SECONDS = 5L
        private const val INSTALL_TIMEOUT_SECONDS = 3600L
        private const val REPAIR_TIMEOUT_SECONDS = 1800L
        private const val POLL_MS = 500L
        private const val DETAIL_LIMIT = 400
        private const val GUEST_LOG_TAIL = 240
        private const val LOG_LIMIT_BYTES = 1_048_576L
        private const val LOG_KEEP_BYTES = 262_144

        /** Variables of the app's own environment that must not reach proot or the script. */
        private val DROP = listOf(
            "LD_PRELOAD", "BASH_ENV", "NODE_OPTIONS",
            "TERMUX_EXEC__PROC_SELF_EXE", "TERMUX_EXEC__LD_PRELOAD", "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE",
        )

        /** Builds a launcher from the app's own paths and environment, writing the script out of the assets. */
        fun create(context: Context): DesktopLauncher {
            val filesDir = context.filesDir
            val layer = File(filesDir, "proot_layer").apply { mkdirs() }
            val script = File(layer, "wrapper.sh")
            context.assets.open(ASSET_SCRIPT).use { input -> script.outputStream().use { input.copyTo(it) } }
            val rootfs = File(filesDir, DesktopRuntime.ROOTFS_NAME)
            val storage = DesktopStorage(
                privateRoot = filesDir,
                publicRoot = PublicWorkspace.root(),
                rootfsDir = rootfs,
                chmod = { f, m -> android.system.Os.chmod(f.path, m) },
            )
            return DesktopLauncher(
                bashPath = Environment.getBashPath(context),
                scriptFile = script,
                layerDir = layer,
                rootfs = rootfs,
                logFile = File(filesDir, "logs/desktop.log"),
                baseEnvironment = Environment.buildProcessEnvironment(context, 0),
                prootPath = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath,
                storage = storage,
                apkPath = context.applicationInfo.sourceDir,
                nativeLibDir = context.applicationInfo.nativeLibraryDir,
                hostPackage = context.packageName,
                x11LogFile = File(filesDir, "logs/x11.log"),
                density = context.resources.displayMetrics.density,
            )
        }
    }
}

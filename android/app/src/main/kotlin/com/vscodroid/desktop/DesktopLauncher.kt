package com.vscodroid.desktop

import android.content.Context
import com.vscodroid.storage.PublicWorkspace
import com.vscodroid.util.Environment
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Starts, stops and checks the Desktop Mode screen stack (work package WP3).
 *
 * The whole proot command line lives in `assets/desktop/wrapper.sh`; this class
 * only sets the environment that script reads, runs it with the app's own bash,
 * and watches the two ports. It holds the one long-lived process (proot), because
 * the service of WP5 has to own that process group.
 *
 * FALLBACK. If the first start does not bring both ports up, it is tried again
 * once with PROOT_NO_SECCOMP=1. When that works the choice is written to
 * `proot_layer/seccomp.fallback` and used from then on, so a device that needs it
 * pays the failed attempt once.
 *
 * It writes no user-visible text. [StartResult] is what WP5's screen explains,
 * with strings in `res/values`. The session secret is never logged and never
 * written outside the guest.
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
    private val readyTimeoutSeconds: Long = READY_TIMEOUT_SECONDS,
) {
    sealed class StartResult {
        /** [password] is this session's VNC secret, for the viewer address only. */
        data class Running(val password: String, val usedSeccompFallback: Boolean) : StartResult()
        object NotProvisioned : StartResult()
        data class Failed(val detail: String) : StartResult()
    }

    data class Status(val vncUp: Boolean, val webUp: Boolean) {
        val healthy: Boolean get() = vncUp && webUp
    }

    private val fallbackMarker = File(layerDir, "seccomp.fallback")
    private var session: Process? = null

    /** The process of the running session, for the service that owns it. */
    fun process(): Process? = session

    fun start(geometry: String): StartResult {
        if (!File(rootfs, "usr/bin/env").exists()) return StartResult.NotProvisioned
        require(GEOMETRY.matches(geometry)) { "geometry must be WIDTHxHEIGHT" }
        stop()
        storage.ensurePrivateTree()
        storage.ensurePublicTree()
        storage.cleanOrphans()
        rotateLog()
        val plan = storage.plan()
        val password = newPassword()
        val remembered = fallbackMarker.isFile
        val first = launch(plan, geometry, password, noSeccomp = remembered)
        if (first == null) return StartResult.Running(password, remembered)
        if (remembered) return StartResult.Failed(first)
        stop()
        val second = launch(plan, geometry, password, noSeccomp = true)
        if (second == null) {
            runCatching { fallbackMarker.writeText("1\n") }
            return StartResult.Running(password, true)
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
        return runScript("desktop_stop", emptyMap(), 20)?.trim()?.toIntOrNull() ?: 0
    }

    fun status(): Status = Status(portOpen(VNC_PORT), portOpen(WEB_PORT))

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

    /** Null when both ports came up, otherwise why not. */
    private fun launch(plan: DesktopStorage.Plan, geometry: String, password: String, noSeccomp: Boolean): String? {
        logFile.parentFile?.mkdirs()
        val env = environment(plan, geometry, password, noSeccomp)
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
            if (status().healthy) return null
            Thread.sleep(POLL_MS)
        }
        return "ports did not open in ${readyTimeoutSeconds}s: ${logTail()} | ${guestLogTail()}"
    }

    private fun environment(plan: DesktopStorage.Plan, geometry: String, password: String, noSeccomp: Boolean): Map<String, String> {
        val env = HashMap(baseEnvironment)
        for (k in DROP) env.remove(k)
        env["PROOT"] = prootPath
        env["DESKTOP_ROOT"] = rootfs.absolutePath
        env["DESKTOP_DIR"] = layerDir.absolutePath
        env["DESKTOP_BINDS"] = plan.binds.joinToString("\n") { it.spec() }
        env["DESKTOP_GEOMETRY"] = geometry
        env["DESKTOP_VNC_PW"] = password
        env["DESKTOP_NO_SECCOMP"] = if (noSeccomp) "1" else "0"
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
        val env = environment(plan, "1280x720", "unused", fallbackMarker.isFile)
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

    /**
     * The end of the guest's own logs (display server, web bridge, editor), which live
     * inside the unpacked system and so cannot be read by the person using the phone.
     * They go into the failure text the screen shows, so a failed start can be reported
     * from a screenshot. None of them holds the session secret: the password is handed
     * to the display server in one process's environment, never on a command line or in a log.
     */
    private fun guestLogTail(): String = listOf("xvnc", "websockify", "codium").joinToString(" | ") { name ->
        val tail = runCatching { File(rootfs, "tmp/$name.log").readText().takeLast(GUEST_LOG_TAIL).replace('\n', ' ') }
            .getOrDefault("(none)")
        "$name: $tail"
    }

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (_: IOException) {
        false
    }

    private fun newPassword(): String {
        val random = SecureRandom()
        // VNC authentication reads only the first 8 characters of a password.
        return String(CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }

    companion object {
        const val VNC_PORT = 5901
        const val WEB_PORT = 6080
        const val ASSET_SCRIPT = "desktop/wrapper.sh"
        private val GEOMETRY = Regex("^[0-9]{3,4}x[0-9]{3,4}$")
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
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
            )
        }
    }
}

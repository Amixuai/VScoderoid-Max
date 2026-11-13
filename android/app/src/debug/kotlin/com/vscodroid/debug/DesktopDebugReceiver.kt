package com.vscodroid.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.system.Os
import android.util.Log
import com.vscodroid.desktop.DesktopLauncher
import com.vscodroid.desktop.DesktopPins
import com.vscodroid.desktop.DesktopRuntime
import com.vscodroid.desktop.RootfsProvisioner
import com.vscodroid.util.Environment
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only. Lets a person drive WP2 (the Debian root file system) before
 * the real Desktop Mode screens exist (WP5). Nothing here ships.
 *
 * Answered in `files/logs/desktop-wp2.txt` (WP3 actions too) with one line per
 * step, `DESKTOP-WP2-<step>|PASS|FAIL|SKIP|RUNNING|detail`. WP3 actions, below the
 * WP2 ones, are DESKTOP_STACK (install the screen packages), DESKTOP_START
 * (`--es geometry 1080x2000`), DESKTOP_STOP, DESKTOP_STATUS and DESKTOP_STORAGE_TEST
 * (WP3a: runs vsc_storage_test.sh). The viewer address a start prints is
 * http://127.0.0.1:6080/vnc.html and the password is in the START line.
 *
 * WP2 actions:
 *
 *   com.vscodroid.debug.DESKTOP_PROVISION
 *       Runs [RootfsProvisioner]. Optional extras override the pins file, so a
 *       URL and digest can be tried before they are committed:
 *           --es url <https URL>  --es sha256 <64 hex>  --el bytes <size in bytes>
 *
 *   com.vscodroid.debug.DESKTOP_CHECK
 *       Runs the WP2 acceptance commands under PRoot: `cat /etc/os-release`
 *       (must say Debian) and `apt-get update` (must exit 0).
 *
 * The work runs on a plain thread and the receiver returns at once, because a
 * broadcast may not be held open for the minutes a download takes. So the app has
 * to stay open (in the foreground) while it runs; the terminal it is started from
 * is the app. One job at a time.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run on a device.
 */
class DesktopDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        if (!running.compareAndSet(false, true)) {
            DesktopDebug.write(app, listOf("DESKTOP-WP2-busy|FAIL|another job is still running"))
            return
        }
        val url = intent.getStringExtra("url")
        val sha256 = intent.getStringExtra("sha256")
        val bytes = intent.getLongExtra("bytes", -1L)
        Thread {
            try {
                when (action) {
                    ACTION_PROVISION -> DesktopDebug.provision(app, url, sha256, bytes)
                    ACTION_CHECK -> DesktopDebug.check(app)
                    ACTION_STACK -> DesktopDebug.stack(app)
                    ACTION_START -> DesktopDebug.start(app, intent.getStringExtra("geometry") ?: "1280x720")
                    ACTION_STOP -> DesktopDebug.stop(app)
                    ACTION_STATUS -> DesktopDebug.status(app)
                    ACTION_STORAGE_TEST -> DesktopDebug.storageTest(app)
                }
            } catch (e: Exception) {
                DesktopDebug.write(app, listOf("DESKTOP-WP2-error|FAIL|${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                running.set(false)
            }
        }.start()
    }

    private companion object {
        const val ACTION_PROVISION = "com.vscodroid.debug.DESKTOP_PROVISION"
        const val ACTION_CHECK = "com.vscodroid.debug.DESKTOP_CHECK"
        const val ACTION_STACK = "com.vscodroid.debug.DESKTOP_STACK"
        const val ACTION_START = "com.vscodroid.debug.DESKTOP_START"
        const val ACTION_STOP = "com.vscodroid.debug.DESKTOP_STOP"
        const val ACTION_STATUS = "com.vscodroid.debug.DESKTOP_STATUS"
        const val ACTION_STORAGE_TEST = "com.vscodroid.debug.DESKTOP_STORAGE_TEST"
        val running = AtomicBoolean(false)
    }
}

internal object DesktopDebug {
    private const val TAG = "VSCodroid.DesktopWp2"
    private const val COMMAND_TIMEOUT_SECONDS = 240L
    private const val DETAIL_LIMIT = 400

    fun write(context: Context, lines: List<String>) {
        lines.forEach { Log.i(TAG, it) }
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        File(dir, "desktop-wp2.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun provisioner(
        context: Context,
        pins: DesktopPins,
        progress: (RootfsProvisioner.Progress) -> Unit = {},
    ) = DesktopRuntime.provisioner(context, pins, progress)

    // ------------------------------------------------------------------ WP3, WP3a

    @Volatile private var launcher: DesktopLauncher? = null

    private fun launcher(context: Context): DesktopLauncher =
        launcher ?: DesktopLauncher.create(context).also { launcher = it }

    fun stack(context: Context) {
        write(context, listOf("DESKTOP-WP3-stack|RUNNING|apt-get install, several minutes"))
        val failure = launcher(context).installStack()
        write(context, listOf(if (failure == null) "DESKTOP-WP3-stack|PASS|installed" else "DESKTOP-WP3-stack|FAIL|$failure".take(DETAIL_LIMIT)))
    }

    fun start(context: Context, geometry: String) {
        write(context, listOf("DESKTOP-WP3-start|RUNNING|$geometry"))
        val result = launcher(context).start(geometry)
        val line = when (result) {
            is DesktopLauncher.StartResult.Running ->
                "DESKTOP-WP3-start|PASS|http://127.0.0.1:6080/vnc.html?autoconnect=1&resize=scale&password=${result.password} seccompFallback=${result.usedSeccompFallback}"
            is DesktopLauncher.StartResult.NotProvisioned -> "DESKTOP-WP3-start|FAIL|run DESKTOP_PROVISION first"
            is DesktopLauncher.StartResult.Failed -> "DESKTOP-WP3-start|FAIL|${result.detail}"
        }
        write(context, listOf(line.take(DETAIL_LIMIT)))
    }

    fun stop(context: Context) {
        val left = launcher(context).stop()
        write(context, listOf("DESKTOP-WP3-stop|PASS|$left leftover processes ended"))
    }

    fun status(context: Context) {
        val s = launcher(context).status()
        write(context, listOf("DESKTOP-WP3-status|${if (s.healthy) "PASS" else "FAIL"}|vnc=${s.vncUp} web=${s.webUp}"))
    }

    fun storageTest(context: Context) {
        val l = launcher(context)
        val script = File(DesktopRuntime.rootfsDir(context), "root/vsc_storage_test.sh")
        context.assets.open("desktop/vsc_storage_test.sh").use { i -> script.outputStream().use { i.copyTo(it) } }
        Os.chmod(script.path, 0x1ED)
        write(context, listOf("DESKTOP-WP3a-storage|RUNNING|minutes; apt-get install build-essential python3 nodejs npm cargo golang first for full coverage"))
        val (exit, out) = l.runInGuest(
            listOf("/bin/bash", "/root/vsc_storage_test.sh", "/root/workspace/.vsc-storage-test", "/root/.vsc-storage-test"),
            timeoutSeconds = 3000, withBinds = true,
        )
        val lines = out.lines().filter { it.startsWith("STORAGE-") }
        val full = File(context.filesDir, "logs/desktop-storage-test.txt")
        full.writeText(lines.joinToString("\n", postfix = "\n"))
        write(context, listOf("DESKTOP-WP3a-storage|${if (exit == 0) "PASS" else "FAIL"}|exit $exit, ${lines.size} lines in logs/desktop-storage-test.txt"))
    }

    fun provision(context: Context, url: String?, sha256: String?, bytes: Long) {
        var pins = DesktopPins.load(context)
        if (url != null && sha256 != null && bytes > 0) pins = pins.withRootfs(url, sha256, bytes)
        write(context, listOf("DESKTOP-WP2-provision|RUNNING|starting"))
        val result = provisioner(context, pins) { p ->
            write(context, listOf("DESKTOP-WP2-provision|RUNNING|${p.phase} ${p.done}/${p.total}"))
        }.provision()
        val line = when (result) {
            is RootfsProvisioner.Result.Ready -> "DESKTOP-WP2-provision|PASS|ready"
            is RootfsProvisioner.Result.NotPinned ->
                "DESKTOP-WP2-provision|FAIL|no URL and digest are pinned; pass --es url, --es sha256 and --el bytes"
            else -> "DESKTOP-WP2-provision|FAIL|$result"
        }
        write(context, listOf(line.take(DETAIL_LIMIT)))
    }

    fun check(context: Context) {
        val pins = DesktopPins.load(context)
        val steps = ArrayList<String>()
        val provisioner = provisioner(context, pins)
        val rootfs = provisioner.rootfsDir
        // `isReady` compares against the pins file, which may differ from what an
        // override run installed; the tree itself is what the commands need.
        if (!File(rootfs, "bin").exists() && !File(rootfs, "usr/bin").exists()) {
            write(context, listOf("DESKTOP-WP2-rootfs|FAIL|no Debian tree at ${rootfs.name}; run DESKTOP_PROVISION first"))
            return
        }
        steps += "DESKTOP-WP2-rootfs|PASS|tree present"
        write(context, steps + "DESKTOP-WP2-os-release|RUNNING|")

        val os = runInGuest(context, rootfs, "cat /etc/os-release", 30)
        steps += if (os.exit == 0 && os.output.contains("Debian", ignoreCase = true)) {
            "DESKTOP-WP2-os-release|PASS|" + os.output.lineSequence().firstOrNull { it.startsWith("PRETTY_NAME") }.orEmpty()
        } else {
            "DESKTOP-WP2-os-release|FAIL|exit ${os.exit}: ${os.output}"
        }
        write(context, steps + "DESKTOP-WP2-apt-update|RUNNING|")

        val apt = runInGuest(context, rootfs, "apt-get update", COMMAND_TIMEOUT_SECONDS)
        steps += if (apt.exit == 0) {
            "DESKTOP-WP2-apt-update|PASS|" + apt.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        } else {
            "DESKTOP-WP2-apt-update|FAIL|exit ${apt.exit}: ${apt.output}"
        }
        write(context, steps.map { it.take(DETAIL_LIMIT) })
    }

    private class Outcome(val exit: Int?, val output: String)

    /**
     * The reference command line from the brief (WP3), minus the shared-folder
     * bind, which belongs to the storage layout work. Environment is the app's own
     * process environment, which carries PROOT_LOADER and PROOT_TMP_DIR.
     */
    private fun runInGuest(context: Context, rootfs: File, command: String, timeoutSeconds: Long): Outcome {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
        val argv = listOf(
            proot, "--link2symlink", "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "-c", command,
        )
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        builder.environment().putAll(Environment.buildProcessEnvironment(context, 0))
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            return Outcome(null, "could not start: ${e.message}")
        }
        val output = StringBuffer()
        val reader = Thread {
            process.inputStream.bufferedReader().use { r ->
                val buffer = CharArray(1024)
                while (true) {
                    val n = r.read(buffer)
                    if (n < 0) break
                    output.append(buffer, 0, n)
                    // Keep the tail, which is where an error ends up.
                    if (output.length > 8192) output.delete(0, output.length - 4096)
                }
            }
        }
        reader.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2000)
            return Outcome(null, "timed out after ${timeoutSeconds}s: ${output.toString().replace('\n', ' ')}")
        }
        reader.join(2000)
        return Outcome(process.exitValue(), output.toString().trim().replace('\n', ' '))
    }
}

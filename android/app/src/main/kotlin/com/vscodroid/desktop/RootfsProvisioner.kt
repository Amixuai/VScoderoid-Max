package com.vscodroid.desktop

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Downloads, verifies, unpacks and configures the Debian root file system that
 * Desktop Mode runs under PRoot (work package WP2).
 *
 * BLOCKING. Call it from a worker thread; progress comes back through [progress]
 * and cancellation is polled through [isCancelled].
 *
 * RESTARTABLE AFTER THE APP IS KILLED, AT ANY POINT. The state is the disk, not
 * a flag held in memory:
 *
 *   desktop/download/rootfs.tar.xz.part  the download; a later run resumes it
 *   desktop/debian.partial/              extraction in progress; thrown away on restart
 *   desktop/debian/                      the finished tree, put there by one rename
 *   desktop/debian.extracted             written after that rename; names the digest it came from
 *   desktop/debian.ready                 written last, after configuration
 *
 * A run that finds `debian.ready` matching the pins does nothing. One that finds
 * the tree and a matching `debian.extracted` only redoes the (idempotent)
 * configuration. Anything else rebuilds from the download, which is kept until
 * the end so that a rebuild is not another 50 MB.
 *
 * NOTHING IS DELETED OUTSIDE [desktopDir]. Every removal goes through
 * [GuestFs.deleteTree], which checks the target is a direct child of the folder
 * it is expected to be in and does not follow links.
 *
 * It writes no user-visible text. Outcomes are the [Result] cases; the screen
 * that explains them is WP5's, and its strings belong in `res/values`.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class RootfsProvisioner(
    private val desktopDir: File,
    private val pins: DesktopPins,
    private val freeBytes: () -> Long,
    private val chmod: (File, Int) -> Unit,
    /** Text of assets/desktop/10-compat-aliases.sh; empty skips installing it (a JVM test has no assets). */
    private val compatAliases: String = "",
    /** The system shipped inside the APK. When set, nothing is downloaded and the pins' URL is unused. */
    private val bundled: Bundled? = null,
    /** Name of the Debian tree inside [desktopDir]; the markers and the working folder are named after it. */
    rootfsName: String = "debian",
    private val connect: (String) -> HttpURLConnection = { url -> URL(url).openConnection() as HttpURLConnection },
    private val isCancelled: () -> Boolean = { false },
    private val progress: (Progress) -> Unit = {},
) {
    /**
     * The root file system as shipped in the APK: a `.tar.xz` made by
     * scripts/build-desktop-rootfs.sh with the screen stack, the editor and the
     * toolchain already installed. [sha256] and the sizes come from the
     * `rootfs.meta.json` written next to it; [open] gives a fresh stream of the file.
     */
    class Bundled(val open: () -> InputStream, val sha256: String, val bytes: Long, val unpackedBytes: Long)

    enum class Phase { Preflight, Download, Verify, Extract, Configure, Done }

    /** [total] is 0 when it is not known (extraction counts entries, not bytes). */
    data class Progress(val phase: Phase, val done: Long, val total: Long)

    sealed class Result {
        object Ready : Result()
        object NotPinned : Result()
        data class InsufficientStorage(val neededBytes: Long, val availableBytes: Long) : Result()
        data class Network(val detail: String) : Result()
        data class HashMismatch(val expected: String, val actual: String) : Result()
        data class ExtractFailed(val detail: String) : Result()
        data class ConfigureFailed(val detail: String) : Result()
        object Cancelled : Result()
    }

    val rootfsDir = File(desktopDir, rootfsName)
    private val partialDir = File(desktopDir, "$rootfsName.partial")
    private val downloadDir = File(desktopDir, "download")
    private val partFile = File(downloadDir, "rootfs.tar.xz.part")
    private val extractedStamp = File(desktopDir, "$rootfsName.extracted")
    private val readyMarker = File(desktopDir, "$rootfsName.ready")

    /** True when a previous run finished for exactly the digest these pins name. */
    fun isReady(): Boolean =
        (bundled != null || pins.pinned) && rootfsDir.isDirectory && stampMatches(readyMarker)

    fun provision(): Result {
        try {
            if (isReady()) return done()
            if (bundled == null && !pins.pinned) return Result.NotPinned
            desktopDir.mkdirs()

            val needsBuild = !(rootfsDir.isDirectory && stampMatches(extractedStamp))
            report(Phase.Preflight, 0, 0)
            val needed = if (needsBuild && bundled != null) {
                bundled.unpackedBytes + SAFETY_BYTES
            } else if (needsBuild) {
                val have = if (partFile.isFile) minOf(partFile.length(), pins.rootfsBytes) else 0L
                (pins.rootfsBytes - have) + pins.unpackedBytesEstimate + SAFETY_BYTES
            } else {
                SAFETY_BYTES
            }
            val available = freeBytes()
            if (available < needed) return Result.InsufficientStorage(needed, available)

            if (needsBuild) {
                GuestFs.deleteTree(readyMarker, desktopDir)
                GuestFs.deleteTree(extractedStamp, desktopDir)
                if (bundled != null) {
                    extract({ bundled.open() }, bundled.sha256)?.let { return it }
                } else {
                    download()?.let { return it }
                    verify()?.let { return it }
                    extract({ FileInputStream(partFile) }, null)?.let { return it }
                }
            }

            try {
                configure()
            } catch (e: IOException) {
                return Result.ConfigureFailed(e.message ?: e.javaClass.simpleName)
            }
            writeStamp(readyMarker)
            GuestFs.deleteTree(partFile, downloadDir)
            GuestFs.deleteTree(downloadDir, desktopDir)
            return done()
        } catch (e: CancellationException) {
            return Result.Cancelled
        } catch (e: IOException) {
            // Only what the steps above did not already turn into a Result: a
            // removal or a stamp write that failed.
            return Result.ExtractFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun done(): Result {
        report(Phase.Done, 0, 0)
        return Result.Ready
    }

    // ---------------------------------------------------------------- download

    private fun download(): Result? {
        if (!pins.rootfsUrl.startsWith("https://")) return Result.Network("the pinned URL is not https")
        downloadDir.mkdirs()
        var existing = if (partFile.isFile) partFile.length() else 0L
        if (existing > pins.rootfsBytes) {
            partFile.delete()
            existing = 0L
        }
        if (existing == pins.rootfsBytes) {
            report(Phase.Download, existing, pins.rootfsBytes)
            return null
        }

        val connection = connect(pins.rootfsUrl)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            // A compressed transfer encoding would make the byte count and the
            // Range offsets mean something other than file bytes.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")

            val code = connection.responseCode
            val append: Boolean
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = connection.getHeaderField("Content-Range")
                    if (range == null || !range.startsWith("bytes $existing-")) {
                        // The server answered a different range than asked for. Appending
                        // would corrupt the file, so start over on the next attempt.
                        partFile.delete()
                        return Result.Network("the server resumed at the wrong offset")
                    }
                    append = true
                }
                HttpURLConnection.HTTP_OK -> {
                    // No range support: the whole file follows, so begin again.
                    append = false
                    existing = 0L
                }
                else -> return Result.Network("HTTP $code")
            }

            var done = existing
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partFile, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancellationException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (done + n > pins.rootfsBytes) {
                            throw IOException("the server sent more than the pinned size")
                        }
                        out.write(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > REPORT_EVERY_NANOS) {
                            lastReport = now
                            report(Phase.Download, done, pins.rootfsBytes)
                        }
                    }
                    out.fd.sync()
                }
            }
            report(Phase.Download, done, pins.rootfsBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The partial file stays, so the next run resumes from it.
            return Result.Network(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
        if (partFile.length() != pins.rootfsBytes) {
            return Result.Network("the download stopped at ${partFile.length()} of ${pins.rootfsBytes} bytes")
        }
        return null
    }

    // ------------------------------------------------------------------ verify

    private fun verify(): Result? {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastReport = 0L
        FileInputStream(partFile).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (isCancelled()) throw CancellationException("cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastReport > REPORT_EVERY_NANOS) {
                    lastReport = now
                    report(Phase.Verify, done, pins.rootfsBytes)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != pins.rootfsSha256) {
            // A file that does not match is not worth resuming.
            GuestFs.deleteTree(partFile, downloadDir)
            return Result.HashMismatch(pins.rootfsSha256, actual)
        }
        return null
    }

    // ----------------------------------------------------------------- extract

    /**
     * Unpacks [open]'s `.tar.xz` into the working folder and moves it into place. With
     * [expectedSha] set, the compressed bytes are hashed as they are read and the tree
     * is thrown away if the digest differs (a corrupt APK entry, not a network fault).
     */
    private fun extract(open: () -> InputStream, expectedSha: String?): Result? {
        GuestFs.deleteTree(partialDir, desktopDir)
        if (!partialDir.mkdirs()) return Result.ExtractFailed("could not create the working folder")
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            val source: InputStream = if (expectedSha != null) DigestInputStream(open(), sha) else open()
            BufferedInputStream(source, 64 * 1024).use { buffered ->
                // Not closed on its own: closing the decoder would close `buffered`, which the
                // digest check below still has to read to the end. The outer `use` closes both.
                val xz = XZInputStream(buffered)
                TarExtractor(
                    destRoot = partialDir,
                    stripComponents = if (expectedSha != null) 0 else pins.rootfsStripComponents,
                    chmod = chmod,
                    isCancelled = isCancelled,
                    onProgress = { entries, _ -> report(Phase.Extract, entries.toLong(), 0) },
                ).extract(xz)
                if (expectedSha != null) {
                    // The xz stream can end before the file does (padding); the digest needs every byte.
                    val sink = ByteArray(64 * 1024)
                    while (buffered.read(sink) >= 0) { /* drain */ }
                    val actual = sha.digest().joinToString("") { "%02x".format(it) }
                    if (actual != expectedSha) {
                        GuestFs.deleteTree(partialDir, desktopDir)
                        return Result.HashMismatch(expectedSha, actual)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            GuestFs.deleteTree(partialDir, desktopDir)
            return Result.ExtractFailed(e.message ?: e.javaClass.simpleName)
        }
        GuestFs.deleteTree(rootfsDir, desktopDir)
        if (!partialDir.renameTo(rootfsDir)) {
            return Result.ExtractFailed("could not move the unpacked tree into place")
        }
        writeStamp(extractedStamp)
        return null
    }

    // --------------------------------------------------------------- configure

    /**
     * Idempotent, so it is safe to run again after the app was killed halfway.
     *
     * The apt sandbox line is the one thing here that the brief does not list
     * and that has NOT been proven necessary on a device: apt normally drops to
     * an unprivileged `_apt` user to download, which needs set-uid calls PRoot
     * only emulates. It costs nothing if unneeded. If a device test shows apt
     * working without it, delete it.
     */
    private fun configure() {
        report(Phase.Configure, 0, 0)
        val verified = HashSet<String>()
        writeGuestFile("etc/resolv.conf", pins.dns.joinToString("") { "nameserver $it\n" }, verified)
        writeGuestFile(
            "etc/hosts",
            "127.0.0.1\tlocalhost\n::1\t\tlocalhost ip6-localhost ip6-loopback\n",
            verified,
        )
        val keyring = "/usr/share/keyrings/debian-archive-keyring.gpg"
        writeGuestFile(
            "etc/apt/sources.list.d/debian.sources",
            "Types: deb\nURIs: ${pins.mirror}\nSuites: ${pins.suite} ${pins.suite}-updates\n" +
                "Components: main\nSigned-By: $keyring\n\n" +
                "Types: deb\nURIs: ${pins.securityMirror}\nSuites: ${pins.suite}-security\n" +
                "Components: main\nSigned-By: $keyring\n",
            verified,
        )
        // The image carries an old-style sources.list of its own; left in place it would list every
        // repository twice beside debian.sources.
        writeGuestFile("etc/apt/sources.list", "# Managed by VSCodroid: see /etc/apt/sources.list.d/debian.sources\n", verified)
        writeGuestFile("etc/apt/apt.conf.d/99vscodroid-proot", "APT::Sandbox::User \"root\";\n", verified)

        if (compatAliases.isNotEmpty()) installCompatAliases(compatAliases, verified)
        installDeveloperDefaults(verified)

        GuestFs.ensureDirs(rootfsDir, listOf("root"), chmod, verified)
        GuestFs.ensureDirs(rootfsDir, listOf("root", "workspace"), chmod, verified)
        GuestFs.ensureDirs(rootfsDir, listOf("tmp"), chmod, verified)
        chmod(File(rootfsDir, "tmp"), 0x3FF)
    }

    /**
     * Where global package-manager installs go (brief "Master Blueprint", section 5): into folders that
     * [DesktopStorage] keeps outside the Debian tree and binds back in, so replacing the tree (engine
     * migration) does not lose them. npm, pip and PHP are pointed there by configuration the tools
     * already read; nothing here is a new command. `pip install` works without a virtual environment
     * the way it does on a PC (Debian's "externally managed" refusal is switched off for this system).
     */
    private fun installDeveloperDefaults(verified: MutableSet<String>) {
        writeGuestFile("root/.npmrc", "prefix=/root/.npm-global\n", verified)
        writeGuestFile("etc/pip.conf", "[global]\nbreak-system-packages = true\n\n[install]\nuser = true\n", verified)
        writeGuestFile(
            "etc/profile.d/20-dev-paths.sh",
            "export PATH=\"$DEV_PATH:\$PATH\"\n[ -z \"\${COMPOSER_HOME:-}\" ] && export COMPOSER_HOME=/root/.config/composer\n",
            verified,
        )
        val hook = "[ -r /etc/profile.d/20-dev-paths.sh ] && . /etc/profile.d/20-dev-paths.sh"
        val bashrc = File(rootfsDir, "etc/bash.bashrc")
        val current = if (bashrc.isFile && !java.nio.file.Files.isSymbolicLink(bashrc.toPath())) bashrc.readText() else ""
        if (!current.contains(hook)) {
            val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
            writeGuestFile("etc/bash.bashrc", current + separator + hook + "\n", verified)
        }
    }

    /**
     * WP3b. The alias file goes to /etc/profile.d and /etc/bash.bashrc (read by interactive
     * non-login shells, which profile.d is not) is told to load it once. Scripts and make
     * read neither, so they behave as on any Linux machine.
     */
    private fun installCompatAliases(text: String, verified: MutableSet<String>) {
        writeGuestFile("etc/profile.d/10-compat-aliases.sh", text, verified)
        val hook = "[ -r /etc/profile.d/10-compat-aliases.sh ] && . /etc/profile.d/10-compat-aliases.sh"
        val bashrc = File(rootfsDir, "etc/bash.bashrc")
        val current = if (bashrc.isFile && !java.nio.file.Files.isSymbolicLink(bashrc.toPath())) bashrc.readText() else ""
        if (!current.contains(hook)) {
            val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
            writeGuestFile("etc/bash.bashrc", current + separator + "# VSCodroid: Windows and macOS command names\n" + hook + "\n", verified)
        }
    }

    private fun writeGuestFile(relative: String, content: String, verified: MutableSet<String>) {
        val parts = GuestFs.parts(relative)
        GuestFs.ensureDirs(rootfsDir, parts.dropLast(1), chmod, verified)
        val target = File(rootfsDir, parts.joinToString("/"))
        // A symbolic link here (Debian images have pointed resolv.conf at a
        // run-time path before) is replaced by a file, never written through.
        GuestFs.removeNonDirectory(target)
        target.writeText(content)
        chmod(target, 0x1A4)
    }

    // ------------------------------------------------------------------ stamps

    private fun stampText(): String = "suite=${pins.suite}\nsha256=${bundled?.sha256 ?: pins.rootfsSha256}\n"

    private fun stampMatches(file: File): Boolean =
        file.isFile && runCatching { file.readText() == stampText() }.getOrDefault(false)

    private fun writeStamp(file: File) {
        val temp = File(desktopDir, file.name + ".tmp")
        temp.writeText(stampText())
        if (!temp.renameTo(file)) throw IOException("could not write ${file.name}")
    }

    private fun report(phase: Phase, done: Long, total: Long) = progress(Progress(phase, done, total))

    private companion object {
        /** Directories of the global installs, first on PATH in every shell of the guest. */
        const val DEV_PATH = "/root/.npm-global/bin:/root/.local/bin:/root/.cargo/bin:/root/go/bin:/root/.config/composer/vendor/bin"

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val REPORT_EVERY_NANOS = 250_000_000L

        /** Room left over after the unpacked tree, so a full disk is not the first thing apt finds. */
        const val SAFETY_BYTES = 64L * 1024 * 1024
    }
}

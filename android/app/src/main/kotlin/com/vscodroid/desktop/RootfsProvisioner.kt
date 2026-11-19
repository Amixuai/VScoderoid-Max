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
 * THE SYSTEM IS DOWNLOADED ON FIRST USE, NOT SHIPPED IN THE APK. CI publishes the finished
 * system (Debian, the screen stack, the editor, the toolchain) as a release file with a small
 * manifest beside it (url, sha256, bytes, unpacked size). This class reads the manifest,
 * downloads the file in Range chunks into a `.part` file, unpacks it straight into the
 * private folder while hashing it, and deletes the archive the moment the unpack succeeded,
 * so the phone never holds the archive and the tree together for longer than the unpack.
 *
 * THE NETWORK MAY DISAPPEAR FOR HOURS. A failed or stalled chunk is retried forever with a
 * growing pause (the screen shows "waiting for the network"); every byte already written
 * stays, and the next request starts at the end of the `.part` file. Only the user (cancel)
 * or a permanent answer from the server (404, 403 and the like) ends the wait.
 *
 * RESTARTABLE AFTER THE APP IS KILLED, AT ANY POINT. The state is the disk, not
 * a flag held in memory:
 *
 *   desktop/download/rootfs.manifest.json  the manifest the `.part` file belongs to
 *   desktop/download/rootfs.tar.xz.part    the download; a later run resumes it
 *   desktop/debian.partial/                extraction in progress; thrown away on restart
 *   desktop/debian/                        the finished tree, put there by one rename
 *   desktop/debian.extracted               written after that rename; names the digest it came from
 *   desktop/debian.ready                   written last, after configuration
 *
 * A run that finds `debian.ready` does nothing. One that finds the tree and `debian.extracted`
 * only redoes the (idempotent) configuration. Anything else downloads and unpacks.
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
    /** A system shipped inside the APK (tests and developer builds only). When set, nothing is downloaded. */
    private val bundled: Bundled? = null,
    /** Name of the Debian tree inside [desktopDir]; the markers and the working folder are named after it. */
    rootfsName: String = "debian",
    private val connect: (String) -> HttpURLConnection = { url -> URL(url).openConnection() as HttpURLConnection },
    /** Waits [millis] in small steps; returns early only by throwing on cancel. Replaced in tests. */
    private val pause: (Long) -> Unit = { millis -> sleepCancellable(millis) },
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

    /**
     * Download: bytes of the archive. Waiting: the same counts, while the network is away.
     * Extract: bytes of the archive read so far (not entries), so the bar is true to the end.
     */
    enum class Phase { Preflight, Download, Waiting, Verify, Extract, Configure, Done }

    data class Progress(val phase: Phase, val done: Long, val total: Long)

    /** What the server says the current system is. */
    data class Manifest(
        val url: String,
        val sha256: String,
        val bytes: Long,
        val unpackedBytes: Long,
        val stripComponents: Int,
    )

    private class PermanentFailure(message: String) : IOException(message)

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
    private val manifestCache = File(downloadDir, "rootfs.manifest.json")
    private val extractedStamp = File(desktopDir, "$rootfsName.extracted")
    private val readyMarker = File(desktopDir, "$rootfsName.ready")

    /** True when a previous run finished: the tree is there and the ready marker names this suite. */
    fun isReady(): Boolean =
        rootfsDir.isDirectory && readyMarker.isFile &&
            runCatching { readyMarker.readText().startsWith("suite=${pins.suite}\n") }.getOrDefault(false)

    fun provision(): Result {
        try {
            if (isReady()) return done()
            if (bundled == null && !pins.downloadable) return Result.NotPinned
            desktopDir.mkdirs()

            val needsBuild = !(rootfsDir.isDirectory && stampMatches(extractedStamp))
            var stampSha = bundled?.sha256 ?: ""
            if (needsBuild) {
                report(Phase.Preflight, 0, 0)
                GuestFs.deleteTree(readyMarker, desktopDir)
                GuestFs.deleteTree(extractedStamp, desktopDir)
                if (bundled != null) {
                    val needed = bundled.unpackedBytes + SAFETY_BYTES
                    val available = freeBytes()
                    if (available < needed) return Result.InsufficientStorage(needed, available)
                    extract({ bundled.open() }, bundled.sha256, bundled.bytes, 0)?.let { return it }
                } else {
                    val manifest = when (val m = fetchManifest()) {
                        is ManifestResult.Failed -> return m.result
                        is ManifestResult.Found -> m.manifest
                    }
                    stampSha = manifest.sha256
                    val have = if (partFile.isFile) minOf(partFile.length(), manifest.bytes) else 0L
                    // The archive plus the unpacked tree exist together while unpacking, then the archive goes.
                    val needed = (manifest.bytes - have) + manifest.unpackedBytes + SAFETY_BYTES
                    val available = freeBytes()
                    if (available < needed) return Result.InsufficientStorage(needed, available)
                    download(manifest)?.let { return it }
                    extract({ FileInputStream(partFile) }, manifest.sha256, manifest.bytes, manifest.stripComponents)
                        ?.let { return it }
                }
                // The archive is not needed again: the tree stands on its own.
                GuestFs.deleteTree(partFile, downloadDir)
                GuestFs.deleteTree(manifestCache, downloadDir)
                GuestFs.deleteTree(downloadDir, desktopDir)
                writeStamp(extractedStamp, stampSha)
            } else {
                stampSha = runCatching { readStampSha(extractedStamp) }.getOrDefault("")
            }

            try {
                configure()
            } catch (e: IOException) {
                return Result.ConfigureFailed(e.message ?: e.javaClass.simpleName)
            }
            writeStamp(readyMarker, stampSha)
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

    // ---------------------------------------------------------------- manifest

    private sealed class ManifestResult {
        class Found(val manifest: Manifest) : ManifestResult()
        class Failed(val result: Result) : ManifestResult()
    }

    /**
     * Reads the manifest, waiting for the network as long as it takes. A manifest saved by an
     * earlier run is used when the network is away, so a half-finished download resumes at
     * once, even offline.
     */
    private fun fetchManifest(): ManifestResult {
        downloadDir.mkdirs()
        var attempt = 0
        while (true) {
            if (isCancelled()) throw CancellationException("cancelled")
            val fromNetwork = try {
                parseManifest(httpGetText(pins.manifestUrl))
            } catch (e: PermanentFailure) {
                return ManifestResult.Failed(Result.Network(e.message ?: "the manifest was refused"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: org.json.JSONException) {
                return ManifestResult.Failed(Result.Network("the manifest is not valid"))
            } catch (e: IllegalArgumentException) {
                return ManifestResult.Failed(Result.Network(e.message ?: "the manifest is not valid"))
            }
            if (fromNetwork != null) {
                // A new system than the half-downloaded one: the old bytes are no use.
                val cached = readCachedManifest()
                if (cached != null && cached.sha256 != fromNetwork.sha256) GuestFs.deleteTree(partFile, downloadDir)
                runCatching { manifestCache.writeText(manifestToJson(fromNetwork)) }
                return ManifestResult.Found(fromNetwork)
            }
            val cached = readCachedManifest()
            if (cached != null) return ManifestResult.Found(cached)
            attempt++
            report(Phase.Waiting, 0, 0)
            pause(backoffMillis(attempt))
        }
    }

    private fun readCachedManifest(): Manifest? =
        if (manifestCache.isFile) runCatching { parseManifest(manifestCache.readText()) }.getOrNull() else null

    private fun httpGetText(url: String): String {
        val connection = connect(url)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code in 400..499 && code != 408 && code != 429) throw PermanentFailure("HTTP $code at $url")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_MANIFEST_BYTES) throw PermanentFailure("the manifest is too large")
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- download

    /**
     * Downloads [m] into the `.part` file in chunks of [CHUNK_BYTES], each its own Range request that
     * starts where the file ends. Returns null when the file is complete, or a permanent failure.
     */
    private fun download(m: Manifest): Result? {
        downloadDir.mkdirs()
        var attempt = 0
        var existing = if (partFile.isFile) partFile.length() else 0L
        if (existing > m.bytes) {
            partFile.delete()
            existing = 0L
        }
        report(Phase.Download, existing, m.bytes)
        while (existing < m.bytes) {
            if (isCancelled()) throw CancellationException("cancelled")
            val before = existing
            try {
                existing = fetchChunk(m, existing)
            } catch (e: PermanentFailure) {
                return Result.Network(e.message ?: "the download was refused")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Bytes written before the failure are kept: re-read where the file ends.
                existing = if (partFile.isFile) partFile.length() else 0L
            }
            if (existing > before) {
                attempt = 0
                report(Phase.Download, existing, m.bytes)
            } else {
                attempt++
                report(Phase.Waiting, existing, m.bytes)
                pause(backoffMillis(attempt))
            }
        }
        if (partFile.length() != m.bytes) {
            partFile.delete()
            return Result.Network("the download ended at ${partFile.length()} of ${m.bytes} bytes")
        }
        report(Phase.Download, m.bytes, m.bytes)
        return null
    }

    /** One chunk. Returns the new length of the `.part` file; throws [IOException] on a network fault. */
    private fun fetchChunk(m: Manifest, from: Long): Long {
        if (!m.url.startsWith("https://")) throw PermanentFailure("the download address is not https")
        val last = minOf(from + CHUNK_BYTES, m.bytes) - 1
        val connection = connect(m.url)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            // A compressed transfer encoding would make the byte count and the Range offsets
            // mean something other than file bytes.
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=$from-$last")
            val code = connection.responseCode
            val append: Boolean
            val limit: Long
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = connection.getHeaderField("Content-Range")
                    if (range == null || !range.startsWith("bytes $from-")) {
                        // A different range than asked for: appending would corrupt the file.
                        throw IOException("the server answered a different range")
                    }
                    append = true
                    limit = last + 1
                }
                HttpURLConnection.HTTP_OK -> {
                    // No range support: the whole file follows, so begin again from zero.
                    append = false
                    limit = m.bytes
                }
                416 -> {
                    // The file is shorter than we think or we are already at its end.
                    if (from > 0) partFile.delete()
                    throw IOException("range not satisfiable")
                }
                else -> {
                    if (code in 400..499 && code != 408 && code != 429) throw PermanentFailure("HTTP $code at ${m.url}")
                    throw IOException("HTTP $code")
                }
            }
            var done = if (append) from else 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partFile, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            if (isCancelled()) throw CancellationException("cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (done + n > limit) throw IOException("the server sent more than was asked for")
                            out.write(buffer, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastReport > REPORT_EVERY_NANOS) {
                                lastReport = now
                                report(Phase.Download, done, m.bytes)
                            }
                        }
                    } finally {
                        // Whatever arrived before a fault or a cancel is real and kept for the resume.
                        runCatching { out.flush(); out.fd.sync() }
                    }
                }
            }
            return done
        } finally {
            connection.disconnect()
        }
    }

    // ----------------------------------------------------------------- extract

    /**
     * Unpacks [open]'s `.tar.xz` into the working folder and moves it into place. The compressed
     * bytes are hashed as they are read and the tree is thrown away if the digest differs. The
     * progress is the share of the archive already read ([archiveBytes] in total).
     */
    private fun extract(open: () -> InputStream, expectedSha: String, archiveBytes: Long, strip: Int): Result? {
        GuestFs.deleteTree(partialDir, desktopDir)
        if (!partialDir.mkdirs()) return Result.ExtractFailed("could not create the working folder")
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastReport = 0L
            val counted = object : java.io.FilterInputStream(open()) {
                override fun read(): Int = super.read().also { if (it >= 0) tick(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) tick(it) }

                private fun tick(n: Int) {
                    read += n
                    val now = System.nanoTime()
                    if (now - lastReport > REPORT_EVERY_NANOS) {
                        lastReport = now
                        report(Phase.Extract, read, archiveBytes)
                    }
                }
            }
            report(Phase.Extract, 0, archiveBytes)
            BufferedInputStream(DigestInputStream(counted, sha), 64 * 1024).use { buffered ->
                // Not closed on its own: closing the decoder would close `buffered`, which the
                // digest check below still has to read to the end. The outer `use` closes both.
                val xz = XZInputStream(buffered)
                TarExtractor(
                    destRoot = partialDir,
                    stripComponents = strip,
                    chmod = chmod,
                    isCancelled = isCancelled,
                    onProgress = { _, _ -> },
                ).extract(xz)
                // The xz stream can end before the file does (padding); the digest needs every byte.
                val sink = ByteArray(64 * 1024)
                while (buffered.read(sink) >= 0) { /* drain */ }
                report(Phase.Extract, archiveBytes, archiveBytes)
                val actual = sha.digest().joinToString("") { "%02x".format(it) }
                if (actual != expectedSha) {
                    GuestFs.deleteTree(partialDir, desktopDir)
                    // A file that does not match is not worth resuming.
                    GuestFs.deleteTree(partFile, downloadDir)
                    GuestFs.deleteTree(manifestCache, downloadDir)
                    return Result.HashMismatch(expectedSha, actual)
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

    private fun stampText(sha: String): String = "suite=${pins.suite}\nsha256=$sha\n"

    /** A stamp exists and names this suite; the digest in it is for the record, not compared. */
    private fun stampMatches(file: File): Boolean =
        file.isFile && runCatching { file.readText().startsWith("suite=${pins.suite}\n") }.getOrDefault(false)

    private fun readStampSha(file: File): String =
        file.readLines().firstOrNull { it.startsWith("sha256=") }?.removePrefix("sha256=").orEmpty()

    private fun writeStamp(file: File, sha: String) {
        val temp = File(desktopDir, file.name + ".tmp")
        temp.writeText(stampText(sha))
        if (!temp.renameTo(file)) throw IOException("could not write ${file.name}")
    }

    // ---------------------------------------------------------------- manifest json

    private fun parseManifest(text: String): Manifest {
        val o = org.json.JSONObject(text)
        require(o.optInt("schema", 1) == 1) { "unsupported manifest schema" }
        val m = Manifest(
            url = o.getString("url"),
            sha256 = o.getString("sha256").lowercase(),
            bytes = o.getLong("bytes"),
            unpackedBytes = o.getLong("unpacked_bytes"),
            stripComponents = o.optInt("strip_components", 0),
        )
        require(m.url.startsWith("https://")) { "the manifest names a download that is not https" }
        require(SHA256.matches(m.sha256)) { "the manifest digest is not a SHA-256" }
        require(m.bytes > 0 && m.unpackedBytes > 0) { "the manifest sizes are not positive" }
        require(m.stripComponents in 0..2) { "strip_components out of range" }
        return m
    }

    private fun manifestToJson(m: Manifest): String = org.json.JSONObject()
        .put("schema", 1).put("url", m.url).put("sha256", m.sha256).put("bytes", m.bytes)
        .put("unpacked_bytes", m.unpackedBytes).put("strip_components", m.stripComponents).toString()

    private fun report(phase: Phase, done: Long, total: Long) = progress(Progress(phase, done, total))

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** Pause before retry number [attempt] (1 based): 2, 4, 8, 16, 30, 30, ... seconds. */
        fun backoffMillis(attempt: Int): Long =
            minOf(30_000L, 1_000L shl minOf(attempt.coerceAtLeast(1), 5))

        /** Sleeps [millis] in half-second steps so a cancel is noticed within half a second. */
        internal fun sleepCancellable(millis: Long) {
            var left = millis
            while (left > 0) {
                Thread.sleep(minOf(500L, left))
                left -= 500L
                if (Thread.currentThread().isInterrupted) throw CancellationException("cancelled")
            }
        }

        private const val MAX_MANIFEST_BYTES = 64 * 1024

        /** Directories of the global installs, first on PATH in every shell of the guest. */
        const val DEV_PATH = "/root/.npm-global/bin:/root/.local/bin:/root/.cargo/bin:/root/go/bin:/root/.config/composer/vendor/bin"

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val REPORT_EVERY_NANOS = 250_000_000L

        /** Room left over after the unpacked tree, so a full disk is not the first thing apt finds. */
        /** One Range request asks for this much; a stall or fault costs at most one chunk's re-request. */
        const val CHUNK_BYTES = 8L * 1024 * 1024

        const val SAFETY_BYTES = 64L * 1024 * 1024
    }
}

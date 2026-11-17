package com.vscodroid.desktop

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CancellationException

/**
 * Unpacks a tar stream into [destRoot], for the Debian root file system.
 *
 * A stream reader rather than a file extractor: the archive is `.tar.xz`, so the
 * caller hands in the decompressed stream and nothing is ever unpacked to an
 * intermediate `.tar` that would need a gigabyte more of free space.
 *
 * WHAT IT DOES ABOUT THINGS AN APP CANNOT DO
 *
 *  - Hard links become symbolic links (relative, to the first copy). The
 *    internal storage can hold a hard link, but PRoot's `--link2symlink` makes
 *    its own hard links symlinks, and mixing the two breaks its bookkeeping, so
 *    the tree is made consistent from the start.
 *  - Character and block devices, and FIFOs, are skipped and reported. An app
 *    cannot create them; PRoot binds the host's `/dev` over the top.
 *  - Ownership is not applied. The app can only own what it creates, and
 *    PRoot's fake-root mode presents everything as root inside the guest.
 *  - Set-uid and set-gid bits are dropped. They mean nothing without real root.
 *  - Directories always get owner rwx and files owner rw, so a restrictive mode
 *    in the archive can never leave a tree the app cannot later delete.
 *
 * WHAT IT REFUSES
 *
 *  - Any `..` component, so no entry can name a place outside [destRoot].
 *  - Writing below a path an earlier entry turned into a symbolic link, which is
 *    how a hostile archive reaches outside the tree. See [GuestFs.ensureDirs].
 *  - A header whose checksum is wrong, which is what a damaged download looks
 *    like once the digest check has been skipped or misconfigured.
 *
 * Handled formats: ustar, GNU long names (`L`, `K`), and pax extended headers
 * (`x`, with `path`, `linkpath` and `size`; `g` is skipped).
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled or run.
 */
class TarExtractor(
    private val destRoot: File,
    /** Leading path components dropped from every name and hard-link target (a rootfs archive that sits in one wrapper folder). */
    private val stripComponents: Int = 0,
    private val chmod: (File, Int) -> Unit,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (entries: Int, bytes: Long) -> Unit = { _, _ -> },
) {
    data class Summary(
        val entries: Int,
        val files: Int,
        val directories: Int,
        val symlinks: Int,
        val hardLinksAsSymlinks: Int,
        val skipped: List<String>,
        val bytes: Long,
    )

    private val verified = HashSet<String>()

    fun extract(input: InputStream): Summary {
        var entries = 0
        var files = 0
        var directories = 0
        var symlinks = 0
        var hardLinks = 0
        var bytes = 0L
        val skipped = ArrayList<String>()

        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize = -1L
        var longName: String? = null
        var longLink: String? = null

        val header = ByteArray(BLOCK)
        while (true) {
            if (isCancelled()) throw CancellationException("cancelled")
            if (!readBlock(input, header)) break
            if (isZeroBlock(header)) break
            verifyChecksum(header)

            val type = (header[156].toInt() and 0xFF).toChar()
            val declaredSize = parseNumeric(header, 124, 12)
            val mode = parseNumeric(header, 100, 8).toInt()

            when (type) {
                'x' -> {
                    val pax = parsePax(readData(input, declaredSize))
                    paxPath = pax["path"] ?: paxPath
                    paxLink = pax["linkpath"] ?: paxLink
                    paxSize = pax["size"]?.toLongOrNull() ?: paxSize
                    continue
                }
                'g' -> {
                    skipData(input, declaredSize)
                    continue
                }
                'L' -> {
                    longName = cString(readData(input, declaredSize))
                    continue
                }
                'K' -> {
                    longLink = cString(readData(input, declaredSize))
                    continue
                }
                else -> Unit
            }

            val ustar = isPosixUstar(header)
            val prefix = if (ustar) cString(header, 345, 155) else ""
            val baseName = cString(header, 0, 100)
            val name = longName ?: paxPath ?: if (prefix.isNotEmpty()) "$prefix/$baseName" else baseName
            val link = longLink ?: paxLink ?: cString(header, 157, 100)
            val size = if (paxSize >= 0) paxSize else declaredSize
            longName = null
            longLink = null
            paxPath = null
            paxLink = null
            paxSize = -1L

            val parts = GuestFs.parts(name).drop(stripComponents)
            if (parts.isEmpty()) {
                // "./", the root itself.
                skipData(input, size)
                continue
            }
            GuestFs.ensureDirs(destRoot, parts.dropLast(1), chmod, verified)
            val target = File(destRoot, parts.joinToString("/"))

            when (type) {
                '5' -> {
                    makeDirectory(target, mode)
                    directories++
                }
                '0', '\u0000', '7' -> {
                    GuestFs.removeNonDirectory(target)
                    FileOutputStream(target).use { out -> copyExact(input, out, size) }
                    skipPadding(input, size)
                    chmod(target, (mode and 0x1FF) or GuestFs.FILE_OWNER)
                    files++
                    bytes += size
                }
                '2' -> {
                    GuestFs.removeNonDirectory(target)
                    Files.createSymbolicLink(target.toPath(), Paths.get(link))
                    symlinks++
                }
                '1' -> {
                    val targetParts = GuestFs.parts(link).drop(stripComponents)
                    if (targetParts.isEmpty()) throw TarException("hard link to nothing: $name")
                    val original = File(destRoot, targetParts.joinToString("/"))
                    if (!Files.exists(original.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw TarException("hard link $name points at $link, which has not been extracted")
                    }
                    GuestFs.removeNonDirectory(target)
                    val relative = target.parentFile.toPath().relativize(original.toPath())
                    Files.createSymbolicLink(target.toPath(), Paths.get(relative.toString()))
                    hardLinks++
                }
                else -> {
                    skipData(input, size)
                    skipped.add("$name (type '$type')")
                }
            }
            entries++
            if (entries % PROGRESS_EVERY == 0) onProgress(entries, bytes)
        }
        onProgress(entries, bytes)
        return Summary(entries, files, directories, symlinks, hardLinks, skipped, bytes)
    }

    class TarException(message: String) : IOException(message)

    private fun makeDirectory(target: File, mode: Int) {
        val path = target.toPath()
        if (Files.isSymbolicLink(path)) Files.delete(path)
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!target.mkdir()) throw TarException("could not create ${target.name}")
        } else if (!target.isDirectory) {
            GuestFs.removeNonDirectory(target)
            if (!target.mkdir()) throw TarException("could not create ${target.name}")
        }
        chmod(target, (mode and 0x3FF) or GuestFs.DIR_OWNER)
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < BLOCK) {
            val n = input.read(block, read, BLOCK - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("the archive ends inside a header")
            }
            read += n
        }
        return true
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_HEADER_DATA) throw TarException("an extended header claims $size bytes")
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) throw EOFException("the archive ends inside an extended header")
            read += n
        }
        skipPadding(input, size)
        return data
    }

    private fun copyExact(input: InputStream, out: FileOutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("the archive ends inside a file")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun skipData(input: InputStream, size: Long) {
        skipExact(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (BLOCK - (size % BLOCK)) % BLOCK
        skipExact(input, pad)
    }

    private fun skipExact(input: InputStream, count: Long) {
        val scratch = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("the archive ends early")
            remaining -= n
        }
    }

    private fun isZeroBlock(block: ByteArray): Boolean = block.all { it == 0.toByte() }

    private fun verifyChecksum(h: ByteArray) {
        var sum = 0L
        for (i in 0 until BLOCK) {
            sum += if (i in 148..155) 0x20 else (h[i].toInt() and 0xFF)
        }
        val stored = parseNumeric(h, 148, 8)
        if (sum != stored) throw TarException("a header checksum does not match: the archive is damaged")
    }

    private fun isPosixUstar(h: ByteArray): Boolean {
        // "ustar\0" followed by version "00". GNU's "ustar  " does not use the prefix field.
        val magic = String(h, 257, 5, StandardCharsets.US_ASCII)
        return magic == "ustar" && h[262].toInt() == 0
    }

    private fun parseNumeric(h: ByteArray, offset: Int, length: Int): Long {
        if ((h[offset].toInt() and 0x80) != 0) {
            var value = (h[offset].toInt() and 0x7F).toLong()
            for (i in 1 until length) value = (value shl 8) or (h[offset + i].toLong() and 0xFF)
            return value
        }
        var value = 0L
        var started = false
        for (i in offset until offset + length) {
            val c = h[i].toInt()
            if (c == 0 || c == ' '.code) {
                if (started) break else continue
            }
            if (c < '0'.code || c > '7'.code) throw TarException("a header holds a number that is not octal")
            value = value * 8 + (c - '0'.code)
            started = true
        }
        return value
    }

    private fun cString(h: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && h[end].toInt() != 0) end++
        return String(h, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun cString(data: ByteArray): String = cString(data, 0, data.size)

    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var pos = 0
        while (pos < data.size) {
            var space = pos
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) throw TarException("an extended header is malformed")
            val length = String(data, pos, space - pos, StandardCharsets.US_ASCII).toIntOrNull()
                ?: throw TarException("an extended header record has no length")
            if (length <= space - pos + 1 || pos + length > data.size) {
                throw TarException("an extended header record has a bad length")
            }
            val record = String(data, space + 1, pos + length - space - 2, StandardCharsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            pos += length
        }
        return out
    }

    private companion object {
        const val BLOCK = 512
        const val PROGRESS_EVERY = 200
        const val MAX_HEADER_DATA = 1L shl 20
    }
}

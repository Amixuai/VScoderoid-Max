package com.vscodroid.desktop

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The few file operations Desktop Mode does on the Debian tree, written so that
 * a symbolic link inside that tree can never steer one of them outside it.
 *
 * The tree arrives from the network as an archive and is full of links, some of
 * them absolute (`/usr/bin`). Following such a link from app code would resolve
 * against Android's own root, not the guest's. So nothing here follows one:
 * every directory on the way to a file is checked to be a real directory, and a
 * removal deletes a link as a link.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled.
 */
internal object GuestFs {

    /** Owner read/write/execute: what any directory has to have for the app to manage it. */
    const val DIR_OWNER = 0x1C0

    /** Owner read/write: the least a file needs for the app to read it back and delete it. */
    const val FILE_OWNER = 0x180

    /**
     * Makes sure every directory in [dirs] (relative path components below
     * [root]) exists and is a real directory, creating the missing ones.
     * Throws if one of them is a symbolic link or a file.
     *
     * [verified] remembers what has already been checked, so a tree of tens of
     * thousands of entries does not re-check `usr` for each of them.
     */
    fun ensureDirs(root: File, dirs: List<String>, chmod: (File, Int) -> Unit, verified: MutableSet<String>) {
        val key = StringBuilder()
        for (part in dirs) {
            if (key.isNotEmpty()) key.append('/')
            key.append(part)
            val relative = key.toString()
            if (relative in verified) continue
            val dir = File(root, relative)
            val path = dir.toPath()
            if (Files.isSymbolicLink(path)) {
                throw IOException("$relative is a symbolic link where a directory is needed")
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!dir.mkdir()) throw IOException("could not create $relative")
                chmod(dir, 0x1ED)
            } else if (!dir.isDirectory) {
                throw IOException("$relative exists and is not a directory")
            }
            verified.add(relative)
        }
    }

    /**
     * Removes whatever is at [target] unless it is a directory. A symbolic link
     * goes as a link; the thing it points at is never touched.
     */
    fun removeNonDirectory(target: File) {
        val path = target.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.delete(path)
            return
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("${target.name} is a directory where a file or link is expected")
        }
        Files.deleteIfExists(path)
    }

    /** Splits an archive or guest path into plain components; rejects `..`. */
    fun parts(raw: String): List<String> {
        val out = ArrayList<String>()
        for (p in raw.split('/')) {
            if (p.isEmpty() || p == ".") continue
            if (p == "..") throw IOException("a path climbs out of the tree: $raw")
            out.add(p)
        }
        return out
    }

    /**
     * Deletes [target], which has to be a direct child of [requiredParent], and
     * only that.
     *
     * The checks come before any removal: the parent is compared by canonical
     * path, so a `desktop` that had itself been swapped for a link elsewhere
     * fails instead of being emptied. [target] being a link is removed as a link.
     * Directories below it are never followed.
     */
    fun deleteTree(target: File, requiredParent: File) {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val expected = requiredParent.canonicalFile
        if (target.parentFile?.canonicalFile != expected) {
            throw IOException("refusing to delete ${target.name}: it is not directly inside the expected folder")
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A guest may have left a directory read-only; deleting its
                // children needs write and search permission on it.
                val f = dir.toFile()
                f.setReadable(true, true)
                f.setWritable(true, true)
                f.setExecutable(true, true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

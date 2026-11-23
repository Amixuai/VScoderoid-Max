package com.vscodroid.desktop

import java.io.File

/**
 * The plain-text exchange between the editor's file dialogs (inside the Debian system) and the
 * Android system picker (STATUS: unit-tested on a JVM only; never run against a device).
 *
 * The Debian side offers the editor a file-chooser service on its private message bus
 * (wrapper.sh). When the editor opens Open Folder, Open File or Save, that service writes a
 * request into the X server's folder, which is the only folder both sides can see:
 *
 *     vsc-pick-req    <id> NEWLINE <kind> NEWLINE <suggested name> NEWLINE
 *
 * the Desktop screen (patched Termux:X11 MainActivity, vscStartPicker) opens Android's own picker for it and writes the answer:
 *
 *     vsc-pick-res    <id> NEWLINE <path> NEWLINE <path> NEWLINE ...
 *
 * No path means the person cancelled (Android's Back). The paths are the same text on both
 * sides, because the phone's storage is bound into the Debian system at the same place.
 */
object PickerProtocol {
    const val REQUEST_FILE = "vsc-pick-req"
    const val RESPONSE_FILE = "vsc-pick-res"

    enum class Kind(val word: String) {
        Folder("dir"),
        File("file"),
        Files("files"),
        Save("save"),
    }

    data class Request(val id: String, val kind: Kind, val name: String)

    /** Null for anything that is not a well-formed request: it is data from another process. */
    fun parse(text: String): Request? {
        val lines = text.split('\n')
        if (lines.size < 2) return null
        val id = lines[0].trim()
        if (id.isEmpty() || id.length > 40 || !id.all { it.isLetterOrDigit() }) return null
        val kind = Kind.values().firstOrNull { it.word == lines[1].trim() } ?: return null
        return Request(id, kind, lines.getOrNull(2)?.trim().orEmpty().take(200))
    }

    /** The answer text: the request's id, then one path per line (none when cancelled). */
    fun response(id: String, paths: List<String>): String =
        (listOf(id) + paths.filter { it.isNotEmpty() && '\n' !in it }).joinToString("\n") + "\n"

    /**
     * The path for a document id the picker returned, or null when it is not a file of the
     * phone's own storage or SD card (a cloud provider cannot be opened as a path).
     * `primary:Documents/app` becomes `<sharedRoot>/Documents/app`; the downloads provider
     * names real files `raw:/storage/emulated/0/Download/x`.
     */
    fun pathForDocument(authority: String?, docId: String, sharedRoot: File): String? = when (authority) {
        "com.android.externalstorage.documents" -> pathForExternalDocId(docId, sharedRoot)
        "com.android.providers.downloads.documents" ->
            docId.removePrefix("raw:").takeIf { docId.startsWith("raw:/") && ".." !in it.split('/') }
        else -> null
    }

    private fun pathForExternalDocId(docId: String, sharedRoot: File): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1)
        if (relative.split('/').any { it == ".." }) return null
        val base = when (volume) {
            "primary" -> sharedRoot
            "home" -> File(sharedRoot, "Documents")
            else -> File("/storage/$volume")
        }
        return (if (relative.isEmpty()) base else File(base, relative)).path
    }
}

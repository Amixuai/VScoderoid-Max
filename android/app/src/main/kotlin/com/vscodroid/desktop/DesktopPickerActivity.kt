package com.vscodroid.desktop

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.vscodroid.storage.PublicWorkspace
import com.vscodroid.util.Logger
import java.io.File

/**
 * Opens Android's own folder/file picker when the editor in Desktop Mode asks for Open Folder,
 * Open File or Save As, and hands the chosen path back (see [PickerProtocol]). The picker shows
 * hidden folders, opens a folder with one tap and closes with Android's Back, which is why it
 * replaces the Linux file dialog on a touch screen.
 *
 * It has no screen of its own: the picker is on top of it the whole time, and it closes when the
 * picker does. Starting in the workspace folder is the picker's own initial location.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class DesktopPickerActivity : ComponentActivity() {

    private var request: PickerProtocol.Request? = null

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        answer(if (result.resultCode == RESULT_OK && data != null) pathsFrom(data) else emptyList())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = PickerProtocol.parse(intent.getStringExtra(EXTRA_REQUEST).orEmpty())
        request = parsed
        if (parsed == null) {
            finish()
            return
        }
        // After a recreation the result is delivered to the launcher by itself.
        if (savedInstanceState != null) return
        try {
            launcher.launch(pickerIntent(parsed))
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "No file picker on this device: ${e.message}")
            answer(emptyList())
        }
    }

    private fun pickerIntent(r: PickerProtocol.Request): Intent {
        val intent = when (r.kind) {
            PickerProtocol.Kind.Folder -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            PickerProtocol.Kind.File -> Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            PickerProtocol.Kind.Files -> Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // A generic type, so that the picker does not add an extension of its own to the name.
            PickerProtocol.Kind.Save -> Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, r.name.ifEmpty { "untitled" })
        }
        PublicWorkspace.initialPickerUri()?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        return intent
    }

    /** The paths of what was chosen; empty when it cannot be a path (a cloud folder, say). */
    private fun pathsFrom(data: Intent): List<String> {
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        data.data?.let { if (it !in uris) uris.add(it) }
        val folder = request?.kind == PickerProtocol.Kind.Folder
        val root = PublicWorkspace.sharedRoot()
        return uris.mapNotNull { uri ->
            val docId = runCatching {
                if (folder) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
            }.getOrNull() ?: return@mapNotNull null
            val path = PickerProtocol.pathForDocument(uri.authority, docId, root) ?: return@mapNotNull null
            path.takeIf { if (folder) File(it).isDirectory else File(it).exists() }
        }
    }

    private fun answer(paths: List<String>) {
        val r = request
        if (r != null) {
            runCatching {
                val dir = File(filesDir, "${DesktopRuntime.ROOTFS_NAME}/tmp/.X11-unix")
                dir.mkdirs()
                val tmp = File(dir, PickerProtocol.RESPONSE_FILE + ".tmp")
                tmp.writeText(PickerProtocol.response(r.id, paths))
                tmp.renameTo(File(dir, PickerProtocol.RESPONSE_FILE))
            }.onFailure { Logger.w(TAG, "Could not write the picker answer: ${it.message}") }
        }
        finish()
    }

    companion object {
        private const val TAG = "DesktopPicker"
        const val EXTRA_REQUEST = "request"
    }
}

package com.vscodroid.desktop

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.vscodroid.storage.PublicWorkspace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes down why Android ended this app's processes the last few times, so a desktop that
 * "just disappeared" (the Not connected screen) has a recorded cause: the system's own words,
 * such as low memory, too many child processes, or an excessive resource use kill. The list is
 * kept in the private files folder and copied beside the user's files as
 * desktop_exit_reasons.txt. UNTESTED on a device.
 */
object DesktopExitLog {
    private const val FILE_NAME = "desktop_exit_reasons.txt"
    private const val KEEP = 8

    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, KEEP)
            val text = format(infos.map { Entry(it.timestamp, it.processName, it.reason, it.status, it.description.orEmpty(), it.pss) })
            File(context.filesDir, FILE_NAME).writeText(text)
            val publicDir = File(android.os.Environment.getExternalStorageDirectory(), PublicWorkspace.HIDDEN_NAME)
            if (publicDir.isDirectory) File(publicDir, FILE_NAME).writeText(text)
        }
    }

    data class Entry(
        val time: Long,
        val process: String,
        val reason: Int,
        val status: Int,
        val description: String,
        val pssKb: Long,
    )

    internal fun format(entries: List<Entry>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("Why Android ended this app's processes (newest first)\n")
        for (e in entries) {
            sb.append(stamp.format(Date(e.time))).append(" | ").append(e.process)
                .append(" | ").append(reasonName(e.reason))
                .append(" | status ").append(e.status)
                .append(" | memory ").append(e.pssKb / 1024).append(" MB")
                .append(" | ").append(e.description).append('\n')
        }
        return sb.toString()
    }

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user (force stop)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
        ApplicationExitInfo.REASON_OTHER -> "other system reason"
        else -> "reason $reason"
    }
}

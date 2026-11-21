package com.vscodroid.desktop

import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vscodroid.R
import com.vscodroid.storage.PublicWorkspace

/**
 * The Desktop Mode screen (brief WP5): the real desktop editor, shown through Termux:X11.
 *
 * While the desktop starts this screen is one line of status text and a progress wheel.
 * Once the X server and the guest are up it opens the Termux:X11 screen (a library in
 * this app, [DesktopViewer.X11_ACTIVITY]) and gets out of the way, so the picture is
 * the whole display with no toolbar, button or menu of ours. Input is Termux:X11's own
 * touch handling plus the keyboard. Leaving with Back keeps the desktop running (this
 * screen then closes itself); Android's own notification Stop action ends it.
 *
 * This setup screen is portrait. It shows the real progress of the first-time download (size,
 * speed, time left) and, apart, of the unpacking, with a Cancel button. The Termux:X11 picture
 * follows the device's own screen size and density.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class DesktopActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var x11Opened = false
    private var lastMemoryWarning = 0L
    private var needsFilesPromptShown = false

    private val listener = DesktopManager.Listener { render() }

    /** Opened by the app's first start to fetch the system: it closes itself when the system is ready. */
    private val setupOnly: Boolean get() = intent?.getBooleanExtra(EXTRA_SETUP_ONLY, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_desktop)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        status = findViewById(R.id.desktop_status)
        findViewById<Button>(R.id.desktop_cancel).setOnClickListener {
            DesktopManager.cancel()
            finish()
        }
        DesktopManager.attach(this)
        DesktopManager.addListener(listener)
        render()
        if (setupOnly) DesktopManager.prefetch(this) else requestStart()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Back from the all-files screen: try again if the user allowed it.
        if (needsFilesPromptShown && PublicWorkspace.hasAllFilesAccess()) {
            needsFilesPromptShown = false
            requestStart()
        }
        // Back from the Termux:X11 screen: the desktop keeps running, and so leaving it
        // leaves this screen too (opening Desktop Mode again brings the picture back).
        // Also when the session ended while this screen was behind the picture: nothing is left to show.
        if (x11Opened) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        DesktopManager.removeListener(listener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val now = SystemClock.elapsedRealtime()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            DesktopManager.state == DesktopState.Running &&
            now - lastMemoryWarning > MEMORY_WARNING_GAP_MS
        ) {
            lastMemoryWarning = now
            Toast.makeText(this, R.string.desktop_low_memory, Toast.LENGTH_LONG).show()
        }
    }

    // ----------------------------------------------------------------------------

    private fun requestStart() {
        val state = DesktopManager.state
        if (state == DesktopState.NotAvailable) return
        ContextCompat.startForegroundService(this, Intent(this, DesktopService::class.java))
    }

    private fun render() {
        val state = DesktopManager.state
        // The session ended while the picture was up (editor closed, guest gone): bring this screen
        // back on top of the picture, which removes it, so no dead black display stays on screen.
        if (x11Opened && state != DesktopState.Running) {
            startActivity(
                Intent(this, DesktopActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            return
        }
        // Prepared in the background (or just now): start the desktop once the system is there.
        if (state == DesktopState.Ready && !x11Opened) {
            if (setupOnly) finish() else requestStart()
        }
        val message = when (state) {
            DesktopState.NotAvailable -> R.string.desktop_not_available
            DesktopState.NotInstalled, DesktopState.Ready, DesktopState.Starting -> R.string.desktop_starting
            DesktopState.Downloading -> R.string.desktop_downloading
            DesktopState.Extracting, DesktopState.Provisioning -> R.string.desktop_unpacking
            DesktopState.Stopping -> R.string.desktop_stopping
            DesktopState.Failed -> when (DesktopManager.lastFailure?.kind) {
                DesktopManager.FailureKind.NeedsAllFilesAccess -> R.string.desktop_need_files
                DesktopManager.FailureKind.NotEnoughStorage -> R.string.desktop_failed_storage
                else -> R.string.desktop_failed
            }
            DesktopState.Running -> null
        }
        if (message != null) {
            status.setText(message)
            status.visibility = View.VISIBLE
            renderTransfer(state)
            findViewById<View>(R.id.desktop_progress).visibility =
                if (state == DesktopState.Failed || state == DesktopState.NotAvailable ||
                    DesktopManager.isPreparing
                ) View.GONE else View.VISIBLE
            findViewById<View>(R.id.desktop_cancel).visibility =
                if (DesktopManager.isPreparing) View.VISIBLE else View.GONE
            val detail = findViewById<TextView>(R.id.desktop_detail)
            val reason = DesktopManager.lastFailure?.detail.orEmpty()
            val showDetail = state == DesktopState.Failed && reason.isNotBlank() &&
                DesktopManager.lastFailure?.kind == DesktopManager.FailureKind.Other
            detail.text = if (showDetail) reason.take(DETAIL_CHARS) else ""
            detail.visibility = if (showDetail) View.VISIBLE else View.GONE
            if (state == DesktopState.Failed &&
                DesktopManager.lastFailure?.kind == DesktopManager.FailureKind.NeedsAllFilesAccess
            ) {
                askForAllFilesAccess()
            }
            return
        }
        status.visibility = View.GONE
        findViewById<View>(R.id.desktop_detail).visibility = View.GONE
        findViewById<View>(R.id.desktop_progress).visibility = View.GONE
        findViewById<View>(R.id.desktop_download_group).visibility = View.GONE
        findViewById<View>(R.id.desktop_unpack_group).visibility = View.GONE
        findViewById<View>(R.id.desktop_cancel).visibility = View.GONE
        if (!x11Opened) openDisplay()
    }

    /** Fills the download and unpack bars from the manager's counts. */
    private fun renderTransfer(state: DesktopState) {
        val download = findViewById<View>(R.id.desktop_download_group)
        val unpack = findViewById<View>(R.id.desktop_unpack_group)
        val p = DesktopManager.progress
        val downloading = state == DesktopState.Downloading
        val unpacking = state == DesktopState.Extracting || state == DesktopState.Provisioning
        download.visibility = if (downloading || unpacking) View.VISIBLE else View.GONE
        unpack.visibility = if (unpacking) View.VISIBLE else View.GONE
        val bar = findViewById<ProgressBar>(R.id.desktop_download_bar)
        val sizes = findViewById<TextView>(R.id.desktop_download_sizes)
        val speed = findViewById<TextView>(R.id.desktop_download_speed)
        if (downloading && p != null && p.total > 0) {
            bar.progress = permille(p.done, p.total)
            sizes.text = getString(
                R.string.desktop_progress_of,
                Formatter.formatFileSize(this, p.done), Formatter.formatFileSize(this, p.total),
            )
            speed.text = if (p.phase == com.vscodroid.desktop.RootfsProvisioner.Phase.Waiting) {
                getString(R.string.desktop_waiting_network)
            } else {
                getString(
                    R.string.desktop_speed, Formatter.formatFileSize(this, DesktopManager.bytesPerSecond),
                ) + "   " + etaText(DesktopManager.etaSeconds())
            }
        } else if (downloading) {
            bar.progress = 0
            sizes.text = ""
            speed.text = if (p?.phase == com.vscodroid.desktop.RootfsProvisioner.Phase.Waiting) {
                getString(R.string.desktop_waiting_network)
            } else ""
        } else if (unpacking) {
            bar.progress = bar.max
            sizes.text = ""
            speed.text = ""
        }
        if (unpacking) {
            val ubar = findViewById<ProgressBar>(R.id.desktop_unpack_bar)
            val utext = findViewById<TextView>(R.id.desktop_unpack_text)
            if (p != null && p.total > 0 && state == DesktopState.Extracting) {
                ubar.progress = permille(p.done, p.total)
                utext.text = getString(R.string.desktop_progress_of, percent(p.done, p.total), "100%") +
                    "   " + etaText(DesktopManager.etaSeconds())
            } else {
                ubar.progress = ubar.max
                utext.text = ""
            }
        }
    }

    private fun permille(done: Long, total: Long): Int =
        if (total <= 0) 0 else (done.coerceIn(0, total) * 1000 / total).toInt()

    private fun percent(done: Long, total: Long): String = "${permille(done, total) / 10}%"

    private fun etaText(seconds: Long): String = when {
        seconds < 0 -> getString(R.string.desktop_eta_unknown)
        seconds < 90 -> getString(R.string.desktop_eta_seconds, seconds.toInt())
        seconds < 3600 -> getString(R.string.desktop_eta_minutes, ((seconds + 30) / 60).toInt())
        else -> getString(R.string.desktop_eta_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
    }

    /** Opens the Termux:X11 screen once per visit, after setting its preferences the first time. */
    private fun openDisplay() {
        x11Opened = true
        val marker = getSharedPreferences("desktop_mode", MODE_PRIVATE)
        if (!marker.getBoolean(KEY_SCREEN_PREFS_SET, false)) {
            // The file Termux:X11's settings screen reads (the default preferences of this app).
            getSharedPreferences("${packageName}_preferences", MODE_PRIVATE).edit {
                for ((key, value) in DesktopViewer.SCREEN_PREFERENCES) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
            marker.edit { putBoolean(KEY_SCREEN_PREFS_SET, true) }
        }
        showPhantomHelpOnce()
        startActivity(Intent().setClassName(packageName, DesktopViewer.X11_ACTIVITY))
    }

    /** The system screen where the user allows all-files access; asked once per visit. */
    private fun askForAllFilesAccess() {
        if (needsFilesPromptShown) return
        needsFilesPromptShown = true
        for (intent in PublicWorkspace.allFilesAccessIntents(this)) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    /** One system dialog, once: Android 12 and newer may stop the desktop's background processes. */
    private fun showPhantomHelpOnce() {
        val prefs = getSharedPreferences("desktop_mode", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PHANTOM_HELP_SHOWN, false)) return
        prefs.edit { putBoolean(KEY_PHANTOM_HELP_SHOWN, true) }
        AlertDialog.Builder(this)
            .setMessage(R.string.desktop_phantom_help)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** Intent extra: opened by the app's first start to fetch the system, then close. */
        const val EXTRA_SETUP_ONLY = "setup_only"

        private const val KEY_PHANTOM_HELP_SHOWN = "phantom_help_shown"
        private const val KEY_SCREEN_PREFS_SET = "x11_screen_prefs_set_v3"
        private const val DETAIL_CHARS = 1200
        private const val MEMORY_WARNING_GAP_MS = 5 * 60 * 1000L
    }
}

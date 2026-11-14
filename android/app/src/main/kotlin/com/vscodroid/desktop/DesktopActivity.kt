package com.vscodroid.desktop

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * The Desktop Mode screen (brief WP5): the real desktop editor, shown through noVNC.
 *
 * There is nothing here to learn that a PC does not have. The screen is a WebView and,
 * while the desktop is not yet up, one line of status text. No toolbar, no floating
 * button, no menu: input is noVNC's own touch gestures (tap, long press for a
 * right click, two-finger scroll, pinch) plus the keyboard, exactly as on any PC that
 * shows a remote desktop. Leaving with Back keeps the desktop running; Android's own
 * notification Stop action ends it.
 *
 * The screen is locked to landscape, so a rotation never resizes the virtual screen.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
class DesktopActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var loadedUrl: String? = null
    private var lastMemoryWarning = 0L
    private var needsFilesPromptShown = false

    private val listener = DesktopManager.Listener { render() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_desktop)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        webView = findViewById(R.id.desktop_web)
        status = findViewById(R.id.desktop_status)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return !DesktopViewer.isViewerOrigin(uri.scheme, uri.host, uri.port)
            }
        }
        DesktopManager.attach(this)
        DesktopManager.addListener(listener)
        render()
        requestStart()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Back from the all-files screen: try again if the user allowed it.
        if (needsFilesPromptShown && PublicWorkspace.hasAllFilesAccess()) {
            needsFilesPromptShown = false
            requestStart()
        }
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        DesktopManager.removeListener(listener)
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
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
        val bounds = windowManager.maximumWindowMetrics.bounds
        val intent = Intent(this, DesktopService::class.java)
            .putExtra(DesktopService.EXTRA_GEOMETRY, DesktopViewer.geometry(bounds.width(), bounds.height()))
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render() {
        val state = DesktopManager.state
        val message = when (state) {
            DesktopState.NotAvailable -> R.string.desktop_not_available
            DesktopState.NotInstalled, DesktopState.Ready, DesktopState.Starting -> R.string.desktop_starting
            DesktopState.Downloading, DesktopState.Extracting, DesktopState.Provisioning -> R.string.desktop_preparing
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
            findViewById<View>(R.id.desktop_progress).visibility =
                if (state == DesktopState.Failed || state == DesktopState.NotAvailable) View.GONE else View.VISIBLE
            webView.visibility = View.INVISIBLE
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
        webView.visibility = View.VISIBLE
        val url = DesktopManager.viewerUrl() ?: return
        if (url != loadedUrl) {
            loadedUrl = url
            webView.loadUrl(url)
            webView.requestFocus()
            showPhantomHelpOnce()
        }
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

    private companion object {
        const val KEY_PHANTOM_HELP_SHOWN = "phantom_help_shown"
        const val DETAIL_CHARS = 1200
        const val MEMORY_WARNING_GAP_MS = 5 * 60 * 1000L
    }
}

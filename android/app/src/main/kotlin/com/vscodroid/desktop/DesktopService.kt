package com.vscodroid.desktop

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vscodroid.R
import com.vscodroid.VSCodroidApp
import com.vscodroid.util.Logger

/**
 * Foreground service that keeps the Desktop Mode session alive (brief WP5).
 *
 * The proot process group is a child of this app's process, so the process has to stay
 * alive while the desktop runs; this service is what holds it. The state itself is in
 * [DesktopManager]. The notification carries Android's own Stop action, which is a
 * system requirement for a foreground service and not a control of the desktop.
 *
 * The notification channel is the app's existing one (VSCodroidApp), so no new
 * channel string exists.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
// InlinedApi: same reasoning as NodeService, the type constant is inlined and the
// manifest declares the type for every release.
@SuppressLint("InlinedApi")
class DesktopService : Service() {

    private val listener = DesktopManager.Listener {
        // A session that failed has nothing left for this service to hold.
        if (DesktopManager.state == DesktopState.Failed) leave()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DesktopManager.stop(this) { leave() }
            return START_NOT_STICKY
        }
        if (!promote()) {
            stopSelf()
            return START_NOT_STICKY
        }
        DesktopManager.addListener(listener)
        val geometry = intent?.getStringExtra(EXTRA_GEOMETRY) ?: DEFAULT_GEOMETRY
        DesktopManager.start(this, geometry)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DesktopManager.removeListener(listener)
        if (DesktopManager.state == DesktopState.Running || DesktopManager.state == DesktopState.Starting) {
            DesktopManager.stop(this)
        }
        super.onDestroy()
    }

    private fun leave() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Enters the foreground, or answers false when the system refuses. */
    private fun promote(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (e: Exception) {
        Logger.e(TAG, "The foreground promotion was refused; starting no desktop", e)
        false
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DesktopActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DesktopService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, VSCodroidApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.desktop_label))
            .setContentText(getString(R.string.desktop_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.desktop_notification_text)))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.desktop_action_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "DesktopService"
        const val ACTION_STOP = "com.vscodroid.desktop.STOP"
        const val EXTRA_GEOMETRY = "geometry"
        const val DEFAULT_GEOMETRY = "1280x720"
        private const val NOTIFICATION_ID = 2
    }
}

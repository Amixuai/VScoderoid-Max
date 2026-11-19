package com.vscodroid.desktop

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.vscodroid.storage.PublicWorkspace
import com.vscodroid.util.Logger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The one owner of Desktop Mode's state for the whole process (brief WP5).
 *
 * The screen ([DesktopActivity]) and the foreground service ([DesktopService]) both
 * talk to this object and never to the launcher directly, so they cannot disagree
 * about what is running. Work runs on one worker thread; listeners are called on the
 * main thread.
 *
 * It writes no text a person reads: a failure is a [FailureKind] that the screen turns
 * into a string resource.
 *
 * STATUS: UNTESTED. Written without an Android SDK; not compiled, not run.
 */
object DesktopManager {

    enum class FailureKind { NeedsAllFilesAccess, NotEnoughStorage, Other }

    data class Failure(val kind: FailureKind, val detail: String)

    fun interface Listener {
        fun onChanged()
    }

    private const val PREFS = "desktop_mode"
    private const val KEY_STATE = "state"
    private const val TAG = "DesktopManager"

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-worker").apply { isDaemon = true } }

    @Volatile private var machine: DesktopStateMachine? = null
    @Volatile private var launcher: DesktopLauncher? = null

    @Volatile var lastFailure: Failure? = null
        private set

    @Volatile var progress: RootfsProvisioner.Progress? = null
        private set

    /** Speed of the current download or unpack, in bytes per second; 0 when unknown or stalled. */
    @Volatile var bytesPerSecond: Long = 0L
        private set

    @Volatile private var cancelRequested = false

    /** True after a session ended on its own, until the next start. */
    @Volatile var sessionEnded = false
        private set
    private val meter = TransferMeter()
    private var meterPhase: RootfsProvisioner.Phase? = null

    /** True while the system is being fetched or unpacked, from any caller. */
    val isPreparing: Boolean
        get() = state == DesktopState.Downloading || state == DesktopState.Extracting || state == DesktopState.Provisioning

    /** Seconds left for the current phase, or -1 when it cannot be said yet. */
    fun etaSeconds(): Long {
        val p = progress ?: return -1L
        if (p.total <= 0L) return -1L
        return TransferMeter.etaSeconds(p.total - p.done, bytesPerSecond)
    }

    /** Idempotent; the first call builds the machine and settles a state left by a dead process. */
    @Synchronized
    fun attach(context: Context) {
        if (machine != null) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val memory = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val m = DesktopStateMachine(
            store = object : DesktopStateMachine.Store {
                override fun read(): String? = prefs.getString(KEY_STATE, null)
                override fun write(value: String) {
                    prefs.edit().putString(KEY_STATE, value).commit()
                }
            },
            totalRamBytes = memory.totalMem,
        )
        val ready = runCatching { DesktopRuntime.provisioner(app).isReady() }.getOrDefault(false)
        m.reconcile(rootfsReady = ready)
        machine = m
    }

    val state: DesktopState get() = machine?.state ?: DesktopState.NotInstalled

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }

    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Starts the desktop, unpacking the system first when this is the first use. Returns at once. */
    fun start(context: Context) {
        val app = context.applicationContext
        attach(app)
        // The system is already on its way (background download): the screen starts the desktop when
        // it reaches Ready. Queueing a start here would restart a download the person just cancelled.
        if (isPreparing) return
        worker.execute {
            try {
                runStart(app)
            } catch (t: Throwable) {
                Logger.e(TAG, "Start failed unexpectedly", t)
                fail(Failure(FailureKind.Other, t.javaClass.simpleName))
            }
        }
    }

    /**
     * Starts fetching and unpacking the system without launching the desktop, so it is ready by
     * the time anyone opens Desktop Mode. Does nothing when it is already there, when the device
     * is below the RAM floor, or when work is already running.
     */
    fun prefetch(context: Context) {
        val app = context.applicationContext
        attach(app)
        if (state != DesktopState.NotInstalled && state != DesktopState.Failed) return
        worker.execute {
            try {
                lastFailure = null
                provisionIfNeeded(app)
            } catch (t: Throwable) {
                Logger.e(TAG, "Prefetch failed unexpectedly", t)
                fail(Failure(FailureKind.Other, t.javaClass.simpleName))
            }
        }
    }

    /**
     * Cancels a download or unpack in progress. What was downloaded stays on the phone and the
     * next start continues from there. Returns at once; the state follows.
     */
    fun cancel() {
        if (isPreparing) cancelRequested = true
    }

    /** Stops the desktop. [onDone] runs on the main thread when nothing of it is left. */
    fun stop(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        attach(app)
        worker.execute {
            try {
                move(DesktopState.Stopping)
                val l = launcher ?: DesktopLauncher.create(app)
                l.stop()
                move(DesktopState.Ready)
            } catch (t: Throwable) {
                Logger.e(TAG, "Stop failed unexpectedly", t)
                fail(Failure(FailureKind.Other, t.javaClass.simpleName))
            } finally {
                main.post(onDone)
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun runStart(app: Context) {
        val m = machine ?: return
        when (m.state) {
            DesktopState.NotAvailable, DesktopState.Starting, DesktopState.Running,
            DesktopState.Extracting, DesktopState.Provisioning, DesktopState.Downloading,
            DesktopState.Stopping,
            -> return
            else -> Unit
        }
        lastFailure = null
        sessionEnded = false
        if (!provisionIfNeeded(app)) return
        if (!PublicWorkspace.hasAllFilesAccess()) {
            fail(Failure(FailureKind.NeedsAllFilesAccess, ""))
            return
        }
        move(DesktopState.Ready)
        move(DesktopState.Starting)
        val l = DesktopLauncher.create(app)
        launcher = l
        when (val r = l.start()) {
            is DesktopLauncher.StartResult.Running -> {
                move(DesktopState.Running)
                watch(l.process())
                watch(l.xServerProcess())
            }
            DesktopLauncher.StartResult.NotProvisioned -> fail(Failure(FailureKind.Other, "the system is missing"))
            is DesktopLauncher.StartResult.Failed -> fail(Failure(FailureKind.Other, r.detail))
        }
    }

    /** Unpacks the system when it is not there yet. False when it did not end ready (the state says why). */
    private fun provisionIfNeeded(app: Context): Boolean {
        val m = machine ?: return false
        if (m.state == DesktopState.NotAvailable) return false
        cancelRequested = false
        val provisioner = DesktopRuntime.provisioner(app, progress = ::onProgress, isCancelled = { cancelRequested })
        if (provisioner.isReady()) {
            if (m.state == DesktopState.NotInstalled || m.state == DesktopState.Failed) move(DesktopState.Ready)
            return true
        }
        synchronized(this) {
            if (isPreparing) return false
            move(DesktopState.Downloading)
        }
        meter.reset()
        meterPhase = null
        bytesPerSecond = 0L
        val result = provisioner.provision()
        progress = null
        bytesPerSecond = 0L
        when (result) {
            RootfsProvisioner.Result.Ready -> {
                move(DesktopState.Provisioning)
                move(DesktopState.Ready)
                return true
            }
            RootfsProvisioner.Result.Cancelled -> {
                move(DesktopState.NotInstalled)
                notifyListeners()
            }
            is RootfsProvisioner.Result.InsufficientStorage ->
                fail(Failure(FailureKind.NotEnoughStorage, "need ${result.neededBytes}, have ${result.availableBytes}"))
            else -> fail(Failure(FailureKind.Other, result.toString().take(300)))
        }
        return false
    }

    /**
     * Ends the session cleanly when its process ends while the screen believes it is running:
     * the editor was closed (as on a PC, nothing is left on the desktop), or the guest died.
     * Everything of the session is stopped, the state goes back to Ready and the screen closes the
     * picture, so the person never stays in front of a dead black display and can open it again.
     */
    private fun watch(process: Process?) {
        if (process == null) return
        Thread({
            runCatching { process.waitFor() }
            val current = launcher
            if (machine?.state == DesktopState.Running &&
                (current?.process() === process || current?.xServerProcess() === process)
            ) {
                Logger.w(TAG, "The desktop session ended; stopping what is left of it")
                sessionEnded = true
                move(DesktopState.Stopping)
                runCatching { current?.stop() }
                move(DesktopState.Ready)
                notifyListeners()
            }
        }, "desktop-watch").apply { isDaemon = true }.start()
    }

    private fun onProgress(p: RootfsProvisioner.Progress) {
        progress = p
        val now = android.os.SystemClock.elapsedRealtime()
        if (p.phase != meterPhase) {
            // Waiting keeps the download's meter: the speed then falls to zero by itself.
            if (p.phase != RootfsProvisioner.Phase.Waiting) {
                meter.reset()
                meterPhase = p.phase
            }
        }
        if (p.phase == RootfsProvisioner.Phase.Download || p.phase == RootfsProvisioner.Phase.Extract) {
            meter.add(now, p.done)
        }
        bytesPerSecond = if (p.phase == RootfsProvisioner.Phase.Waiting) 0L else meter.bytesPerSecond(now)
        when (p.phase) {
            RootfsProvisioner.Phase.Download, RootfsProvisioner.Phase.Waiting -> move(DesktopState.Downloading)
            RootfsProvisioner.Phase.Extract -> move(DesktopState.Extracting)
            RootfsProvisioner.Phase.Configure -> move(DesktopState.Provisioning)
            else -> Unit
        }
        notifyListeners()
    }

    private fun move(next: DesktopState) {
        if (machine?.transition(next) == true) notifyListeners()
    }

    private fun fail(failure: Failure) {
        lastFailure = failure
        Logger.w(TAG, "Desktop Mode failed: ${failure.kind} ${failure.detail}")
        machine?.transition(DesktopState.Failed, failure.detail)
        notifyListeners()
    }

    private fun notifyListeners() {
        main.post { listeners.forEach { it.onChanged() } }
    }
}

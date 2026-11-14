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
 * The session secret lives only here, in memory. It is never persisted, logged or put
 * in a notification.
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
    @Volatile private var secret: String? = null

    @Volatile var lastFailure: Failure? = null
        private set

    @Volatile var progress: RootfsProvisioner.Progress? = null
        private set

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

    /** The address to show, only while the desktop is running. */
    fun viewerUrl(): String? = if (state == DesktopState.Running) secret?.let(DesktopViewer::url) else null

    /** Starts the desktop, unpacking the system first when this is the first use. Returns at once. */
    fun start(context: Context, geometry: String) {
        val app = context.applicationContext
        attach(app)
        worker.execute {
            try {
                runStart(app, geometry)
            } catch (t: Throwable) {
                Logger.e(TAG, "Start failed unexpectedly", t)
                fail(Failure(FailureKind.Other, t.javaClass.simpleName))
            }
        }
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
                secret = null
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

    private fun runStart(app: Context, geometry: String) {
        val m = machine ?: return
        when (m.state) {
            DesktopState.NotAvailable, DesktopState.Starting, DesktopState.Running,
            DesktopState.Extracting, DesktopState.Provisioning, DesktopState.Downloading,
            DesktopState.Stopping,
            -> return
            else -> Unit
        }
        lastFailure = null
        if (!PublicWorkspace.hasAllFilesAccess()) {
            fail(Failure(FailureKind.NeedsAllFilesAccess, ""))
            return
        }
        val provisioner = DesktopRuntime.provisioner(app, progress = ::onProgress)
        if (!provisioner.isReady()) {
            move(if (DesktopRuntime.bundled(app) != null) DesktopState.Extracting else DesktopState.Downloading)
            when (val r = provisioner.provision()) {
                RootfsProvisioner.Result.Ready -> Unit
                is RootfsProvisioner.Result.InsufficientStorage ->
                    return fail(Failure(FailureKind.NotEnoughStorage, "need ${r.neededBytes}, have ${r.availableBytes}"))
                else -> return fail(Failure(FailureKind.Other, r.toString().take(300)))
            }
            move(DesktopState.Provisioning)
        }
        move(DesktopState.Ready)
        move(DesktopState.Starting)
        val l = DesktopLauncher.create(app)
        launcher = l
        when (val r = l.start(geometry)) {
            is DesktopLauncher.StartResult.Running -> {
                secret = r.password
                move(DesktopState.Running)
                watch(l.process())
            }
            DesktopLauncher.StartResult.NotProvisioned -> fail(Failure(FailureKind.Other, "the system is missing"))
            is DesktopLauncher.StartResult.Failed -> fail(Failure(FailureKind.Other, r.detail))
        }
    }

    /** Marks the session failed if its process ends while the screen believes it is running. */
    private fun watch(process: Process?) {
        if (process == null) return
        Thread({
            runCatching { process.waitFor() }
            if (machine?.state == DesktopState.Running && launcher?.process() === process) {
                secret = null
                fail(Failure(FailureKind.Other, "the session ended"))
            }
        }, "desktop-watch").apply { isDaemon = true }.start()
    }

    private fun onProgress(p: RootfsProvisioner.Progress) {
        progress = p
        when (p.phase) {
            RootfsProvisioner.Phase.Download -> move(DesktopState.Downloading)
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

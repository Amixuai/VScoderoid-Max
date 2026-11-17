package com.vscodroid.desktop

/**
 * The states Desktop Mode moves through (brief WP5) and the rules for moving.
 *
 * Pure Kotlin on purpose: no Android type appears here, so every rule below is
 * covered by a JVM unit test (DesktopStateMachineTest). The only thing that
 * touches the platform is [Store], which the app backs with SharedPreferences.
 *
 * Every transition is written through [Store] before [transition] returns, as
 * FirstRunSetup does, so a kill at any point leaves the last state on disk.
 *
 * STATUS: UNTESTED on a device. The rules are unit-tested on a JVM only.
 */
enum class DesktopState {
    /** The device is below the RAM floor. Never leaves this state. */
    NotAvailable,

    /** The Debian system has not been unpacked yet. */
    NotInstalled,

    /** Developer builds only: the pinned download, when the APK carries no system. */
    Downloading,

    /** Unpacking the system that ships inside the APK. */
    Extracting,

    /** Writing the small configuration files into the unpacked system. */
    Provisioning,

    /** Unpacked and ready to start. */
    Ready,

    Starting,
    Running,
    Stopping,
    Failed,
}

class DesktopStateMachine(private val store: Store, totalRamBytes: Long) {

    /** Where the state lives between launches. */
    interface Store {
        fun read(): String?
        fun write(value: String)
    }

    @Volatile
    var state: DesktopState = initial(store, totalRamBytes)
        private set

    /** Why the machine is in [DesktopState.Failed], for the screen. Not persisted. */
    @Volatile
    var failure: String? = null
        private set

    /**
     * Moves to [next] when the rules allow it and answers whether it did. A move the
     * rules refuse changes nothing, so a late callback from a finished attempt cannot
     * drag the machine backwards.
     */
    @Synchronized
    fun transition(next: DesktopState, detail: String? = null): Boolean {
        if (state == next) return true
        if (next !in ALLOWED.getValue(state)) return false
        state = next
        failure = if (next == DesktopState.Failed) detail else null
        store.write(next.name)
        return true
    }

    /**
     * Called once per process, before anything is started. States that describe work
     * in flight cannot be true in a fresh process, because the work died with the old
     * one, so each is mapped to the state that is true now. [rootfsReady] says whether
     * the unpacked system is complete on disk.
     */
    @Synchronized
    fun reconcile(rootfsReady: Boolean) {
        if (state == DesktopState.NotAvailable) return
        val settled = when (state) {
            DesktopState.Starting, DesktopState.Running, DesktopState.Stopping,
            DesktopState.Ready, DesktopState.Failed,
            -> if (rootfsReady) DesktopState.Ready else DesktopState.NotInstalled
            DesktopState.Downloading, DesktopState.Extracting, DesktopState.Provisioning,
            DesktopState.NotInstalled,
            -> if (rootfsReady) DesktopState.Ready else DesktopState.NotInstalled
            DesktopState.NotAvailable -> DesktopState.NotAvailable
        }
        if (settled != state) {
            state = settled
            failure = null
            store.write(settled.name)
        }
    }

    companion object {
        /**
         * Total RAM the device must report. A phone sold as "6 GB" reports about
         * 5.4 to 5.8 GiB through ActivityManager (the kernel and firmware keep the
         * rest), so the floor sits a little under 6 GB nominal. A phone that reports
         * less is a 4 GB class device.
         */
        const val MIN_TOTAL_RAM_BYTES = 5L * 1024 * 1024 * 1024 + 256L * 1024 * 1024

        fun isEligible(totalRamBytes: Long): Boolean = totalRamBytes >= MIN_TOTAL_RAM_BYTES

        /** Below the RAM floor always wins; otherwise the last state written, or NotInstalled. */
        private fun initial(store: Store, totalRamBytes: Long): DesktopState {
            if (!isEligible(totalRamBytes)) return DesktopState.NotAvailable
            val saved = store.read() ?: return DesktopState.NotInstalled
            return DesktopState.values().firstOrNull { it.name == saved && it != DesktopState.NotAvailable }
                ?: DesktopState.NotInstalled
        }

        private val ALLOWED: Map<DesktopState, Set<DesktopState>> = mapOf(
            DesktopState.NotAvailable to emptySet(),
            DesktopState.NotInstalled to setOf(
                DesktopState.Downloading, DesktopState.Extracting, DesktopState.Ready, DesktopState.Failed,
            ),
            DesktopState.Downloading to setOf(DesktopState.Extracting, DesktopState.Failed, DesktopState.NotInstalled),
            DesktopState.Extracting to setOf(DesktopState.Provisioning, DesktopState.Failed, DesktopState.NotInstalled),
            DesktopState.Provisioning to setOf(DesktopState.Ready, DesktopState.Failed, DesktopState.NotInstalled),
            DesktopState.Ready to setOf(DesktopState.Starting, DesktopState.NotInstalled, DesktopState.Failed),
            DesktopState.Starting to setOf(DesktopState.Running, DesktopState.Stopping, DesktopState.Failed, DesktopState.Ready),
            DesktopState.Running to setOf(DesktopState.Stopping, DesktopState.Failed, DesktopState.Ready),
            DesktopState.Stopping to setOf(DesktopState.Ready, DesktopState.Failed),
            DesktopState.Failed to setOf(
                DesktopState.NotInstalled, DesktopState.Extracting, DesktopState.Downloading,
                DesktopState.Ready, DesktopState.Starting,
            ),
        )
    }
}

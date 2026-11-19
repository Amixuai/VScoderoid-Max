package com.vscodroid.desktop

/**
 * Speed and time-left for a transfer, from the byte counts the provisioner reports.
 *
 * Pure Kotlin, no Android type, so it is covered by a JVM test (TransferMeterTest). The
 * clock is passed in; nothing here reads the time.
 *
 * Speed is the average over the last [windowMillis], so one slow second does not make the
 * estimate jump, and it falls to zero when no bytes arrive (the network is gone): the
 * screen then shows "waiting" instead of a stale number.
 *
 * STATUS: unit-tested on a JVM only; never run against a device.
 */
class TransferMeter(private val windowMillis: Long = 8_000L) {

    private class Sample(val atMillis: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    /** Forget everything, for example when a new phase starts. */
    @Synchronized
    fun reset() = samples.clear()

    /** Records that [bytes] have been transferred in total at [nowMillis]. */
    @Synchronized
    fun add(nowMillis: Long, bytes: Long) {
        // A smaller count than before means a restart from zero; the old samples are meaningless.
        if (samples.isNotEmpty() && bytes < samples.last().bytes) samples.clear()
        samples.addLast(Sample(nowMillis, bytes))
        while (samples.size > 2 && nowMillis - samples.first().atMillis > windowMillis) samples.removeFirst()
    }

    /** Bytes per second over the window as of [nowMillis]; 0 when nothing arrived or there is too little data. */
    @Synchronized
    fun bytesPerSecond(nowMillis: Long): Long {
        if (samples.size < 2) return 0L
        val first = samples.first()
        val last = samples.last()
        // Silence since the last sample counts against the speed, so a dead network reads as 0.
        val span = maxOf(nowMillis, last.atMillis) - first.atMillis
        if (span <= 0L) return 0L
        if (nowMillis - last.atMillis > windowMillis) return 0L
        return (last.bytes - first.bytes) * 1000L / span
    }

    companion object {
        /** Seconds left at [bytesPerSecond] for [remaining] bytes, or -1 when it cannot be said. */
        fun etaSeconds(remaining: Long, bytesPerSecond: Long): Long =
            if (remaining <= 0L) 0L else if (bytesPerSecond <= 0L) -1L else (remaining + bytesPerSecond - 1) / bytesPerSecond
    }
}

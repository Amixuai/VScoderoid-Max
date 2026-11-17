package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Desktop Mode state rules (brief WP5). Plain JVM: the machine has no Android type in it. */
class DesktopStateMachineTest {

    private class MemoryStore(var saved: String? = null) : DesktopStateMachine.Store {
        val writes = mutableListOf<String>()
        override fun read(): String? = saved
        override fun write(value: String) {
            saved = value
            writes += value
        }
    }

    private val enoughRam = DesktopStateMachine.MIN_TOTAL_RAM_BYTES
    private val tooLittleRam = DesktopStateMachine.MIN_TOTAL_RAM_BYTES - 1

    @Test
    fun `a device below the RAM floor is not offered Desktop Mode and never leaves that state`() {
        val machine = DesktopStateMachine(MemoryStore(), tooLittleRam)
        assertEquals(DesktopState.NotAvailable, machine.state)
        assertFalse(machine.transition(DesktopState.Extracting))
        machine.reconcile(rootfsReady = true)
        assertEquals(DesktopState.NotAvailable, machine.state)
    }

    @Test
    fun `the floor sits below 6 GB nominal because phones report less than they are sold with`() {
        assertTrue(DesktopStateMachine.isEligible(5_800L * 1024 * 1024), "a 6 GB phone reports about 5.7 GiB")
        assertFalse(DesktopStateMachine.isEligible(4_000L * 1024 * 1024), "a 4 GB class phone must be refused")
    }

    @Test
    fun `a fresh install starts as not installed`() {
        assertEquals(DesktopState.NotInstalled, DesktopStateMachine(MemoryStore(), enoughRam).state)
    }

    @Test
    fun `the saved state is read back, and a saved NotAvailable is ignored once the RAM allows`() {
        assertEquals(DesktopState.Ready, DesktopStateMachine(MemoryStore("Ready"), enoughRam).state)
        assertEquals(DesktopState.NotInstalled, DesktopStateMachine(MemoryStore("NotAvailable"), enoughRam).state)
        assertEquals(DesktopState.NotInstalled, DesktopStateMachine(MemoryStore("nonsense"), enoughRam).state)
    }

    @Test
    fun `the first use walks the whole way to running and back`() {
        val store = MemoryStore()
        val machine = DesktopStateMachine(store, enoughRam)
        val path = listOf(
            DesktopState.Extracting, DesktopState.Provisioning, DesktopState.Ready, DesktopState.Starting,
            DesktopState.Running, DesktopState.Stopping, DesktopState.Ready,
        )
        for (next in path) {
            assertTrue(machine.transition(next), "NotInstalled to Ready to Running must allow $next")
            assertEquals(next, machine.state)
        }
        assertEquals(path.map { it.name }, store.writes, "every transition is written before it returns")
    }

    @Test
    fun `a move the rules refuse changes nothing and writes nothing`() {
        val store = MemoryStore()
        val machine = DesktopStateMachine(store, enoughRam)
        assertFalse(machine.transition(DesktopState.Running))
        assertEquals(DesktopState.NotInstalled, machine.state)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `a late callback cannot drag a running desktop backwards to extracting`() {
        val machine = DesktopStateMachine(MemoryStore("Running"), enoughRam)
        assertFalse(machine.transition(DesktopState.Extracting))
        assertEquals(DesktopState.Running, machine.state)
    }

    @Test
    fun `the failure reason is kept only while failed`() {
        val machine = DesktopStateMachine(MemoryStore("Ready"), enoughRam)
        assertTrue(machine.transition(DesktopState.Failed, "ports did not open"))
        assertEquals("ports did not open", machine.failure)
        assertTrue(machine.transition(DesktopState.Starting))
        assertNull(machine.failure)
    }

    @Test
    fun `a state left by a dead process is settled to the one that is true now`() {
        for (left in listOf("Starting", "Running", "Stopping", "Failed", "Ready")) {
            val ready = DesktopStateMachine(MemoryStore(left), enoughRam).also { it.reconcile(rootfsReady = true) }
            assertEquals(DesktopState.Ready, ready.state, "$left with a complete system")
            val missing = DesktopStateMachine(MemoryStore(left), enoughRam).also { it.reconcile(rootfsReady = false) }
            assertEquals(DesktopState.NotInstalled, missing.state, "$left without one")
        }
        for (left in listOf("Downloading", "Extracting", "Provisioning")) {
            val interrupted = DesktopStateMachine(MemoryStore(left), enoughRam).also { it.reconcile(rootfsReady = false) }
            assertEquals(DesktopState.NotInstalled, interrupted.state, "$left was interrupted")
        }
    }

    @Test
    fun `a failed attempt can be retried from every starting point`() {
        for (next in listOf(DesktopState.NotInstalled, DesktopState.Extracting, DesktopState.Ready, DesktopState.Starting)) {
            val machine = DesktopStateMachine(MemoryStore("Failed"), enoughRam)
            assertTrue(machine.transition(next), "Failed to $next")
        }
    }
}

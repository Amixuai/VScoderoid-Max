package com.vscodroid.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransferMeterTest {

    @Test
    fun `speed is zero until two samples exist`() {
        val m = TransferMeter()
        assertEquals(0L, m.bytesPerSecond(0))
        m.add(0, 0)
        assertEquals(0L, m.bytesPerSecond(0))
    }

    @Test
    fun `steady transfer reads its true speed`() {
        val m = TransferMeter()
        for (s in 0..4) m.add(s * 1000L, s * 2_000_000L)
        assertEquals(2_000_000L, m.bytesPerSecond(4000))
    }

    @Test
    fun `old samples leave the window`() {
        val m = TransferMeter(windowMillis = 4_000)
        m.add(0, 0)
        m.add(1000, 10_000_000) // a fast burst long ago
        for (s in 2..12) m.add(s * 1000L, 10_000_000L + (s - 1) * 100_000L)
        assertEquals(100_000L, m.bytesPerSecond(12_000))
    }

    @Test
    fun `silence reads as zero`() {
        val m = TransferMeter(windowMillis = 4_000)
        m.add(0, 0)
        m.add(1000, 1_000_000)
        assertEquals(0L, m.bytesPerSecond(20_000))
    }

    @Test
    fun `a restart from zero drops the old samples`() {
        val m = TransferMeter()
        m.add(0, 0)
        m.add(1000, 5_000_000)
        m.add(2000, 0)
        m.add(3000, 1_000_000)
        assertEquals(1_000_000L, m.bytesPerSecond(3000))
    }

    @Test
    fun `eta rounds up and says unknown without a speed`() {
        assertEquals(0L, TransferMeter.etaSeconds(0, 100))
        assertEquals(-1L, TransferMeter.etaSeconds(100, 0))
        assertEquals(3L, TransferMeter.etaSeconds(250, 100))
    }
}

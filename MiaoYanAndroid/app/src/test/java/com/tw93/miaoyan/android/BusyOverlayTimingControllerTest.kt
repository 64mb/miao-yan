package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.BusyOverlayTimingController
import com.tw93.miaoyan.android.ui.BusyOverlayStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyOverlayTimingControllerTest {
    @Test
    fun fastOperationNeverBecomesVisible() {
        var now = 1_000L
        val machine = BusyOverlayStateMachine(nowMillis = { now })

        assertEquals(150L, machine.updateBusy(true))
        now += 149L
        assertEquals(1L, machine.advanceToNextDeadline())
        assertFalse(machine.isVisible)
        assertEquals(null, machine.updateBusy(false))
        now += 1_000L
        assertEquals(null, machine.advanceToNextDeadline())
        assertFalse(machine.isVisible)
    }

    @Test
    fun visibleOperationKeepsOriginalMinimumAcrossBusyPulsesAndClockRegression() {
        var now = 2_000L
        val machine = BusyOverlayStateMachine(
            timing = BusyOverlayTimingController(showDelayMillis = 150L, minimumVisibleMillis = 800L),
            nowMillis = { now },
        )

        assertEquals(150L, machine.updateBusy(true))
        now += 150L
        assertEquals(null, machine.advanceToNextDeadline())
        assertTrue(machine.isVisible)

        assertEquals(800L, machine.updateBusy(false))
        now += 300L
        assertEquals(null, machine.updateBusy(true))
        assertTrue(machine.isVisible)
        now -= 100L
        assertEquals(null, machine.updateBusy(true))
        assertTrue(machine.isVisible)

        now = 2_949L
        assertEquals(1L, machine.updateBusy(false))
        assertTrue(machine.isVisible)
        now += 1L
        assertEquals(null, machine.advanceToNextDeadline())
        assertFalse(machine.isVisible)
    }
}

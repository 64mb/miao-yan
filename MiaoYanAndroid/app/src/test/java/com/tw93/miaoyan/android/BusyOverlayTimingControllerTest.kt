package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.BusyOverlayTimingController
import org.junit.Assert.assertEquals
import org.junit.Test

class BusyOverlayTimingControllerTest {
    private val controller = BusyOverlayTimingController()

    @Test
    fun fastOperationsNeverNeedToShowAndVisibleRunsKeepTheirMinimumDuration() {
        assertEquals(150L, controller.delayBeforeShow(alreadyVisible = false))
        assertEquals(0L, controller.delayBeforeShow(alreadyVisible = true))
        assertEquals(800L, controller.delayBeforeHide(shownAtMillis = 1_000L, nowMillis = 1_000L))
        assertEquals(250L, controller.delayBeforeHide(shownAtMillis = 1_000L, nowMillis = 1_550L))
        assertEquals(0L, controller.delayBeforeHide(shownAtMillis = 1_000L, nowMillis = 1_900L))
    }
}

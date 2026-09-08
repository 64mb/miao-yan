package com.tw93.miaoyan.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tw93.miaoyan.android.ui.BusyOverlayStateMachine
import com.tw93.miaoyan.android.ui.BusyOverlayTestTag
import com.tw93.miaoyan.android.ui.DelayedLoadingOverlay
import org.junit.Rule
import org.junit.Test

class BusyOverlayInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renderedOverlayHonorsDelayAndContinuousMinimumAcrossBusyPulseAndLabelChange() {
        composeRule.mainClock.autoAdvance = false
        var monotonicMillis = 0L
        var busy by mutableStateOf(false)
        var label by mutableStateOf("Reloading")
        val machine = BusyOverlayStateMachine(nowMillis = { monotonicMillis })
        composeRule.setContent {
            MaterialTheme {
                DelayedLoadingOverlay(busy = busy, label = label, stateMachine = machine)
            }
        }

        composeRule.runOnIdle { busy = true }
        composeRule.mainClock.advanceTimeByFrame()
        monotonicMillis = 149L
        composeRule.mainClock.advanceTimeBy(149L)
        composeRule.onNodeWithTag(BusyOverlayTestTag).assertDoesNotExist()

        monotonicMillis = 150L
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(BusyOverlayTestTag).assertExists()
        composeRule.onNodeWithText("Reloading").assertExists()

        composeRule.runOnIdle { busy = false }
        composeRule.mainClock.advanceTimeByFrame()
        monotonicMillis = 350L
        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.runOnIdle {
            label = "Syncing"
            busy = true
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(BusyOverlayTestTag).assertExists()
        composeRule.onNodeWithText("Syncing").assertExists()

        monotonicMillis = 949L
        composeRule.mainClock.advanceTimeBy(599L)
        composeRule.runOnIdle { busy = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(BusyOverlayTestTag).assertExists()

        monotonicMillis = 950L
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(BusyOverlayTestTag).assertDoesNotExist()
    }

    @Test
    fun renderedOverlayNeverAppearsForOperationBelowDelay() {
        composeRule.mainClock.autoAdvance = false
        var monotonicMillis = 0L
        var busy by mutableStateOf(false)
        val machine = BusyOverlayStateMachine(nowMillis = { monotonicMillis })
        composeRule.setContent {
            MaterialTheme {
                DelayedLoadingOverlay(busy = busy, label = "Reloading", stateMachine = machine)
            }
        }

        composeRule.runOnIdle { busy = true }
        composeRule.mainClock.advanceTimeByFrame()
        monotonicMillis = 149L
        composeRule.mainClock.advanceTimeBy(149L)
        composeRule.runOnIdle { busy = false }
        composeRule.mainClock.advanceTimeByFrame()
        monotonicMillis = 1_000L
        composeRule.mainClock.advanceTimeBy(851L)

        composeRule.onNodeWithTag(BusyOverlayTestTag).assertDoesNotExist()
    }
}

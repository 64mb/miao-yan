package com.tw93.miaoyan.android

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class PresentationHostInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<PresentationTestActivity>()

    @Test
    fun bundledRevealHandlesKeyboardAndRetainsSlideAcrossRecreation() {
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 0 }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 1 }

        compose.activityRule.scenario.recreate()

        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 1 }
        assertEquals(1, PresentationTestActivity.reportedSlide.get())
    }

    @Test
    fun backExitsThePresentationHost() {
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 0 }

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        compose.onNodeWithText("Presentation closed").assertIsDisplayed()
    }
}
